package com.tcm.ehr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.po.DictionaryBackup;
import com.tcm.ehr.domain.po.DictionaryTerm;
import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.mapper.DictionaryBackupMapper;
import com.tcm.ehr.mapper.DictionaryTermMapper;
import com.tcm.ehr.mapper.DictionaryVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 组织级词典存储：词条落 {@code dictionary_terms}，版本对账落 {@code dictionary_versions}，
 * 导入前存档落 {@code dictionary_backups}。
 *
 * <p><b>组织维度语义</b>：{@code orgId} 为空串 {@code ""} = <b>基础层</b>（全组织共享）。
 * 某组织没有自有词条时<b>读到基础层</b>，而不是读到空 —— 空会让归一把该组织的所有术语
 * 判成「词典里没有」，分数全塌。</p>
 *
 * <p><b>为什么版本号入表</b>：ES 的 {@code _meta.version} 是索引级的，分不清组织。
 * 灌完 ES 只更新 {@code dictionary_versions.indexed_version}，没灌成功就不动它 ——
 * 这样「DB 已改、ES 没跟上」在启动对账时能被真实地看见，而不是被一个索引级标记掩盖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryTermStore {

    /** 基础层组织号：空串，不用 null（null 在唯一索引里互不相等，挡不住重复） */
    public static final String BASE_ORG = "";

    private final DictionaryTermMapper termMapper;
    private final DictionaryBackupMapper backupMapper;
    private final DictionaryVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 读某组织生效的词条：有自有词条读自己的，否则回退基础层。
     *
     * @param orgId 组织号；空串/null 视为基础层
     * @param type  术语类型
     * @return 词条列表；两层都没有返回空列表（不是 null）
     */
    public List<TermEntry> readEffective(String orgId, String type) {
        List<TermEntry> own = read(orgId, type);
        if (!own.isEmpty()) {
            return own;
        }
        return isBase(orgId) ? List.of() : read(BASE_ORG, type);
    }

    /**
     * 读某一层（不回落）的原始词条。
     *
     * @param orgId 组织号；空串 = 基础层
     * @param type  术语类型
     * @return 该层词条
     */
    public List<TermEntry> read(String orgId, String type) {
        String org = norm(orgId);
        List<DictionaryTerm> rows = termMapper.selectList(new QueryWrapper<DictionaryTerm>()
                .eq("org_id", org)
                .eq("type", type));
        List<TermEntry> list = new ArrayList<>(rows.size());
        for (DictionaryTerm r : rows) {
            list.add(toEntry(r));
        }
        return list;
    }

    /**
     * 覆盖写入某组织的整类词条，并更新内容版本。
     *
     * <p>写入与版本更新在<b>同一事务</b>：否则「词条换了、版本没换」会让启动对账
     * 判定「已同步」而跳过重建，ES 里就永远停在这一版。</p>
     *
     * @param orgId   组织号
     * @param type    术语类型
     * @param entries 全量词条
     * @return 新的内容版本
     */
    @Transactional(rollbackFor = Exception.class)
    public String replace(String orgId, String type, List<TermEntry> entries) {
        String org = norm(orgId);
        // 1. 先删该层全部词条，再按新内容重灌（uk_org_type_term 兜并发重复）
        termMapper.delete(new QueryWrapper<DictionaryTerm>()
                .eq("org_id", org)
                .eq("type", type));
        for (TermEntry e : entries) {
            DictionaryTerm row = new DictionaryTerm();
            row.setId(UUID.randomUUID().toString());
            row.setOrgId(org);
            row.setType(type);
            row.setStandardTerm(e.getStandardTerm());
            row.setCode(e.getCode());
            row.setSource(e.getSource());
            row.setAliases(writeJson(e.getAliases()));
            termMapper.insert(row);
        }
        // 2. 内容版本：按标准词排序后取哈希 —— 与插入顺序无关，避免「同一份内容
        //    因导入顺序不同算出两个版本」而白白触发一次 ES 全量重建
        String version = contentVersion(entries);
        upsertVersion(org, type, version);
        return version;
    }

    /**
     * 记录「已灌入 ES」的版本。
     *
     * <p>只在 ES 灌成功<b>之后</b>调用。灌失败绝不能调：调了就是「谎报已同步」，
     * 启动对账会跳过重建，归一永远用旧索引。</p>
     */
    public void markIndexed(String orgId, String type, String indexedVersion) {
        String org = norm(orgId);
        DictionaryVersion v = findVersion(org, type);
        if (v == null) {
            v = new DictionaryVersion();
            v.setOrgId(org);
            v.setType(type);
            v.setVersion(indexedVersion);
        }
        v.setIndexedVersion(indexedVersion);
        v.setIndexedAt(LocalDateTime.now());
        saveVersion(v);
    }

    /** 读版本行；不存在返回 null */
    public DictionaryVersion findVersion(String orgId, String type) {
        String org = norm(orgId);
        return versionMapper.selectOne(new QueryWrapper<DictionaryVersion>()
                .eq("org_id", org)
                .eq("type", type));
    }

    /**
     * 该层是否「已同步到 ES」：有版本行且 indexedVersion 追上 version。
     *
     * <p>基础层改动会让所有组织的行都落后，故额外要求基础层也同步过。</p>
     */
    public boolean isSynced(String orgId, String type) {
        DictionaryVersion v = findVersion(orgId, type);
        if (v == null || v.getVersion() == null || v.getIndexedVersion() == null) {
            return false;
        }
        if (!v.getVersion().equals(v.getIndexedVersion())) {
            return false;
        }
        if (isBase(orgId)) {
            return true;
        }
        // 该组织有自定义词条时不必看基础层（没回落到它）
        if (!read(orgId, type).isEmpty()) {
            return true;
        }
        DictionaryVersion base = findVersion(BASE_ORG, type);
        return base != null && base.getVersion() != null
                && base.getVersion().equals(base.getIndexedVersion());
    }

    /**
     * 导入前存档：把该层当前词条整份存进 {@code dictionary_backups}。
     *
     * @return 备份 id；该层本来就没有词条时返回 null（没东西可回滚）
     */
    public String backup(String orgId, String type, String operator) {
        String org = norm(orgId);
        List<TermEntry> entries = read(org, type);
        if (entries.isEmpty()) {
            return null;
        }
        DictionaryBackup b = new DictionaryBackup();
        b.setId(UUID.randomUUID().toString());
        b.setOrgId(org);
        b.setType(type);
        b.setSnapshot(writeJson(entries));
        b.setCreatedBy(operator);
        b.setCreateTime(LocalDateTime.now());
        backupMapper.insert(b);
        return b.getId();
    }

    /** 直接按快照文本解析词条（列表页要显示每个备份的词条数） */
    public List<TermEntry> readBackupBySnapshot(String snapshot) {
        return readSnapshot(snapshot);
    }

    /** 读某次备份的词条快照 */
    public List<TermEntry> readBackup(String backupId) {
        DictionaryBackup b = backupMapper.selectById(backupId);
        if (b == null) {
            return null;
        }
        return readSnapshot(b.getSnapshot());
    }

    /** 备份是否存在（并校验它属于该组织该类型，防止拿别的组织的备份来覆盖） */
    public boolean backupMatches(String backupId, String orgId, String type) {
        DictionaryBackup b = backupMapper.selectById(backupId);
        return b != null && norm(orgId).equals(norm(b.getOrgId())) && type.equals(b.getType());
    }

    /** 列出某层的历史备份（新的在前） */
    public List<DictionaryBackup> listBackups(String orgId, String type) {
        String org = norm(orgId);
        return backupMapper.selectList(new QueryWrapper<DictionaryBackup>()
                .eq("org_id", org)
                .eq("type", type)
                .orderByDesc("create_time", "id"));
    }

    /**
     * 内容版本：标准词排序后取 SHA-256 前 32 位。
     *
     * <p>排序是必须的：不排序的话同一份词条换个导入顺序就换版本，
     * 每次导入都触发一次全量 ES 重建。</p>
     */
    public String contentVersion(List<TermEntry> entries) {
        List<String> keys = new ArrayList<>(entries.size());
        for (TermEntry e : entries) {
            keys.add(e.getStandardTerm() == null ? "" : e.getStandardTerm().trim());
        }
        keys.sort(String::compareTo);
        String joined = String.join("\u0001", keys);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 不可用不该发生；退化成内容长度+首词，至少不抛异常中断启动
            return "len" + keys.size() + "_" + (keys.isEmpty() ? "" : keys.get(0));
        }
    }

    // ---------------- 内部工具 ----------------

    private void upsertVersion(String org, String type, String version) {
        DictionaryVersion v = findVersion(org, type);
        if (v == null) {
            v = new DictionaryVersion();
            v.setOrgId(org);
            v.setType(type);
            v.setVersion(version);
            saveVersion(v);
        } else if (!version.equals(v.getVersion())) {
            v.setVersion(version);
            saveVersion(v);
        }
    }

    private void saveVersion(DictionaryVersion v) {
        if (findVersion(v.getOrgId(), v.getType()) == null) {
            versionMapper.insert(v);
        } else {
            // ⚠️ 必须用显式条件更新，不能用 updateById：
            // dictionary_versions 是复合主键 (org_id, type)，实体上没有单一 @TableId，
            // updateById 在这种表上拼不出 WHERE，编译期不报错、运行即挂。
            versionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DictionaryVersion>()
                    .eq("org_id", v.getOrgId())
                    .eq("type", v.getType())
                    .set("version", v.getVersion())
                    .set("indexed_version", v.getIndexedVersion())
                    .set("indexed_at", v.getIndexedAt()));
        }
    }

    private TermEntry toEntry(DictionaryTerm r) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(r.getStandardTerm());
        e.setCode(r.getCode());
        e.setSource(r.getSource() == null ? "" : r.getSource());
        e.setAliases(readAliases(r.getAliases()));
        return e;
    }

    private List<String> readAliases(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = objectMapper.readValue(json, List.class);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            // 别名坏掉不能拖垮整次读取：标准词还在，模糊召回退化为只匹标准词
            log.warn("[词典] 别名字段解析失败，退化为空别名: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<TermEntry> readSnapshot(String json) {
        try {
            List<TermEntry> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            log.warn("[词典] 备份快照解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("词典内容序列化失败", e);
        }
    }

    /** null 一律归一成基础层空串 */
    private String norm(String orgId) {
        return orgId == null ? BASE_ORG : orgId;
    }

    private boolean isBase(String orgId) {
        return norm(orgId).isEmpty();
    }
}
