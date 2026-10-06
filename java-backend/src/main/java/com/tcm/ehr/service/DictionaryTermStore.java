package com.tcm.ehr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.utils.TermTypes;
import com.tcm.ehr.domain.po.DictionaryTerm;
import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.domain.po.TermEntry;
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
import java.util.HashMap;
import java.util.Map;
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
    private final DictionaryVersionMapper versionMapper;
    /** 词条读取缓存：键 `org|type`，版本用 dictionary_versions.version（内容哈希） */
    private final com.tcm.ehr.common.utils.VersionedCache<List<TermEntry>> termsCache =
            new com.tcm.ehr.common.utils.VersionedCache<>(64);
    private final ObjectMapper objectMapper;

    /**
     * 版本号缓存（批次 12 · 12a）：键 {@code org|type}，TTL 5 秒 + 写路径主动失效。
     *
     * <p>为什么值得缓存：{@code read} 每次都要查一次 {@code dictionary_versions} 才知道词条缓存是否
     * 失效，而 {@code readEffective} 要读两层 ⇒ 一次读就是 <b>2 次</b>版本查询；NLP 归一按记录反复
     * 调它，同一份版本号被查了成百上千次 —— 这正是 12a 说的「每条查 4 次词典元数据」。</p>
     *
     * <p><b>不脏读的做法</b>：① 写路径（{@link #replace} 与 {@code saveVersion}）主动失效 ⇒
     * 本实例内的改动立即生效；② 仍留 5 秒 TTL，覆盖「别的实例改了词表」的情况 ——
     * 跨实例最多迟 5 秒。词表改动不是高频操作，5 秒的可感延迟远小于「每条记录查一次库」的代价。</p>
     */
    private static final long VERSION_CACHE_TTL_MS = 5_000L;

    /** 版本缓存项：值 + 写入时刻（用于 TTL 判断） */
    private record CachedVersion(DictionaryVersion version, long at) {
    }

    private final java.util.Map<String, CachedVersion> versionCache =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /**
     * 读某组织生效的词条：有自有词条读自己的，否则回退基础层。
     *
     * @param orgId 组织号；空串/null 视为基础层
     * @param type  术语类型
     * @return 词条列表；两层都没有返回空列表（不是 null）
     */
    public List<TermEntry> readEffective(String orgId, String type) {
        List<TermEntry> base = isBase(orgId) ? List.of() : read(BASE_ORG, type);
        List<TermEntry> own = read(orgId, type);
        if (base.isEmpty()) {
            return own;
        }
        if (own.isEmpty()) {
            return base;
        }
        // 基础层 + 本组织：同一标准词以本组织为准（组织层是叠加，不是替换）。
        // 必须与 ES 侧的 org_id IN ('', 本组织) 同一口径 —— 原来「有自有层就不回落」
        // 会让词典页、报告与归一实际用到的不是同一份数据
        java.util.Map<String, TermEntry> merged = new java.util.LinkedHashMap<>();
        for (TermEntry e : base) {
            merged.put(termKey(e), e);
        }
        for (TermEntry e : own) {
            merged.put(termKey(e), e);
        }
        return new ArrayList<>(merged.values());
    }

    /** 合并用的键：标准词（null / 空白归一为「无标准词」） */
    private static String termKey(TermEntry e) {
        return e.getStandardTerm() == null ? "" : e.getStandardTerm().trim();
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
        // 版本取 dictionary_versions.version —— 它是「内容哈希」，内容一变版本必变，
        // 比时间戳可靠（不存在同秒撞版本的问题）；写入侧 replace 还会主动失效一次兜底。
        DictionaryVersion v = cachedVersion(org, type);
        String version = v == null || v.getVersion() == null ? "" : v.getVersion();
        return termsCache.get(org + "|" + type, version, () -> loadTerms(org, type));
    }

    /**
     * 取版本号，带 5 秒 TTL 的缓存（批次 12 · 12a）。
     *
     * <p>写路径会主动失效 ⇒ 本实例内的改动立即生效；跨实例最多迟 {@link #VERSION_CACHE_TTL_MS}。
     * 「版本行不存在」（null）也会被缓存：那种行随后会被 {@code upsertVersion} 写入并失效缓存。</p>
     */
    private DictionaryVersion cachedVersion(String org, String type) {
        String key = org + "|" + type;
        long now = System.currentTimeMillis();
        CachedVersion hit = versionCache.get(key);
        if (hit != null && now - hit.at() < VERSION_CACHE_TTL_MS) {
            return hit.version();
        }
        DictionaryVersion v = findVersion(org, type);
        versionCache.put(key, new CachedVersion(v, now));
        return v;
    }

    /**
     * 提交后让两层缓存失效（批次 26.8）。
     *
     * <p>在事务内提前失效，别的线程可能在词条行尚未提交时就按旧版本回填缓存，
     * 之后一直读到旧内容；推迟到 {@code afterCommit} 才能保证「提交即可见」。
     * 无活动事务时（单测直调 {@code replace}）立即失效。</p>
     */
    private void invalidateCachesAfterCommit(String org, String type) {
        Runnable invalidate = () -> {
            // 主动失效：内容哈希相同（同一份内容重灌）时版本不变，但库里的行 ID 已经换了一批，
            // 缓存里那份 list 仍是可用的等价内容 —— 这里失效是为了让「重灌后立刻读」拿到新行
            termsCache.invalidate(org + "|" + type);
            // 版本缓存同样要失效（批次 12 · 12a）：否则重灌后紧接着的读会拿旧版本号
            invalidateVersionCache(org, type);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    /** 让版本缓存失效（写路径必须调用，否则会读到旧版本号） */
    private void invalidateVersionCache(String org, String type) {
        versionCache.remove(org + "|" + type);
    }

    /**
     * 真正查库装载某层词条。
     *
     * 单独抽出来是为了让 read 走缓存：标准化报告一次要读 8 类 × （基础层 + 本组织），
     * 不缓存就是十几条 selectList，而词表内容在一次报告期间不会变。
     */
    private List<TermEntry> loadTerms(String org, String type) {
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
        // 失效推迟到提交后（批次 26.8）：在事务里提前失效，别的线程可能在词条行尚未提交时
        // 就按旧版本回填缓存，随后一直读到旧内容；提交后再失效才能保证「提交即可见」。
        invalidateCachesAfterCommit(org, type);
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
     * 词典里出现过的全部组织号（含基础层空串），按字典序稳定输出。
     *
     * 给「重放全量索引」用：词条表与版本表都算来源 —— 只查词条表会漏掉「词条被清空、
     * 但版本行还在（ES 里可能仍有残留）」的组织，而那恰恰是最需要重建的情形。
     *
     * @return 组织号列表（去重、稳定排序）
     */
    public List<String> listOrgs() {
        java.util.TreeSet<String> set = new java.util.TreeSet<>();
        for (DictionaryTerm t : termMapper.selectList(
                new QueryWrapper<DictionaryTerm>().select("DISTINCT org_id"))) {
            set.add(t.getOrgId() == null ? BASE_ORG : t.getOrgId());
        }
        for (DictionaryVersion v : versionMapper.selectList(
                new QueryWrapper<DictionaryVersion>().select("DISTINCT org_id"))) {
            set.add(v.getOrgId() == null ? BASE_ORG : v.getOrgId());
        }
        return new ArrayList<>(set);
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
     * 内容版本：标准词排序后取 SHA-256 前 32 位。
     *
     * <p>排序是必须的：不排序的话同一份词条换个导入顺序就换版本，
     * 每次导入都触发一次全量 ES 重建。</p>
     */
    public String contentVersion(List<TermEntry> entries) {
        // 哈希输入必须覆盖「归一判定真正用到的字段」：只哈希标准词的话，
        // 只改别名或编码不会换版本 —— 启动对账判「已同步」，归一继续用旧别名
        List<String> keys = new ArrayList<>(entries.size());
        for (TermEntry e : entries) {
            String std = e.getStandardTerm() == null ? "" : e.getStandardTerm().trim();
            String aliases = e.getAliases() == null ? "" : String.join(",", e.getAliases());
            String code = e.getCode() == null ? "" : e.getCode().trim();
            keys.add(std + "\u0002" + aliases + "\u0002" + code);
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

    /**
     * 该组织归一<b>实际用到</b>的词典状态摘要：5 类词典各自「本组织 → 基础层」的有效版本。
     *
     * <p><b>为什么不能用文件哈希</b>（原 {@code currentVersion()}）：词典源在批次 8b 已从文件
     * 改成这张表，文件哈希从此<b>冻结不变</b> —— 库里 500 条记录的 {@code dictVersion}
     * 全都是同一个值，既无法区分、也<b>不代表真正用的那版词典</b>。</p>
     *
     * <p>实现上只读 {@code dictionary_versions}（每 (org,type) 一行），
     * 是两次轻查询；不重读词条 —— 词条量大（pattern 类两千多条）时重读会拖慢批量解析。</p>
     *
     * @param orgId 组织号；空串/null 视为基础层
     * @return 5 类有效版本拼成的摘要串（未收录的类型跳过）
     */
    public String effectiveDictVersion(String orgId) {
        String org = norm(orgId);
        Map<String, String> base = versionsOf(BASE_ORG);
        Map<String, String> own = org.isEmpty() ? Map.of() : versionsOf(org);
        StringBuilder sb = new StringBuilder();
        for (String type : TermTypes.ALL) {
            // 本组织没配这一类就回退基础层 —— 与 readEffective 的回落口径保持一致
            String v = own.getOrDefault(type, base.get(type));
            if (v != null && !v.isBlank()) {
                sb.append(type).append(':').append(v, 0, Math.min(8, v.length())).append(';');
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 某组织（或基础层）各类词典的内容版本 → 版本串 */
    private Map<String, String> versionsOf(String org) {
        Map<String, String> out = new HashMap<>();
        for (DictionaryVersion v : versionMapper.selectList(
                new QueryWrapper<DictionaryVersion>().eq("org_id", org))) {
            if (v.getType() != null && v.getVersion() != null && !v.getVersion().isBlank()) {
                out.put(v.getType(), v.getVersion());
            }
        }
        return out;
    }

    /**
     * 该组织归一实际覆盖的词典词条总数（5 类求和，按「本组织 → 基础层」取其一）。
     *
     * <p>用来把版本串翻译成「依据 N 条词条」——用户看到的是一个哈希时无从判断它代表什么，
     * 看到「依据 3,589 条词条 · 采集于 2026-10-02」才知道用的是多大的词库。</p>
     */
    public int effectiveTermCount(String orgId) {
        String org = norm(orgId);
        Map<String, Integer> base = countsOf(BASE_ORG);
        Map<String, Integer> own = org.isEmpty() ? Map.of() : countsOf(org);
        int sum = 0;
        for (String type : TermTypes.ALL) {
            Integer n = own.containsKey(type) ? own.get(type) : base.get(type);
            if (n != null) {
                sum += n;
            }
        }
        return sum;
    }

    /** 某组织（或基础层）各类词典的词条数 */
    private Map<String, Integer> countsOf(String org) {
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> row : termMapper.selectMaps(
                new QueryWrapper<DictionaryTerm>().eq("org_id", org).select("type", "COUNT(*) AS n")
                        .groupBy("type"))) {
            Object type = row.get("type");
            Object n = row.get("n");
            if (type != null && n instanceof Number num) {
                out.put(String.valueOf(type), num.intValue());
            }
        }
        return out;
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
        // 版本行已变 ⇒ 版本缓存必须失效，否则写路径自己可能读到旧版本号（批次 12 · 12a）
        invalidateVersionCache(v.getOrgId(), v.getType());
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
