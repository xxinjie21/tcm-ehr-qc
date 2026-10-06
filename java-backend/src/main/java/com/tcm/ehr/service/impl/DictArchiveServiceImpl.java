package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.service.IDictArchiveService;
import com.tcm.ehr.domain.po.DictArchiveTerm;
import com.tcm.ehr.domain.po.DictArchiveVersion;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.mapper.DictArchiveTermMapper;
import com.tcm.ehr.mapper.DictArchiveVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基线归档（批次 17）。
 *
 * <p>每次「基线被改动」后（提案合并通过、或管理员直写导入）都存一份<b>合并后</b>的快照，
 * 供历史查看与回滚。</p>
 *
 * <p><b>限额口径</b>：每 {@code (org_id, type)} 最多保留最近 5 份<b>快照</b>；
 * 超限时只删最老的 {@code dict_archive_term}，{@code dict_archive_version} 元信息
 * <b>永久保留</b> —— 版本号连续性与「某次合并由谁做的」是审计链，删不得。
 * 被清理过快照的版本仍会出现在列表里（{@code snapshotPresent=false}），
 * 但不能再用于回滚。</p>
 *
 * <p>{@code org_id = ''} 是基础层，与各组织层<b>独立计数</b>：
 * 全局基础词库和各组织自有词库的归档历史互不干扰。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictArchiveServiceImpl implements IDictArchiveService {

    /** 每组每 type 保留的归档快照份数 */
    public static final int MAX_SNAPSHOT_VERSIONS = 5;

    private final DictArchiveVersionMapper versionMapper;
    private final DictArchiveTermMapper termMapper;
    private final ObjectMapper objectMapper;

    /**
     * 生成一份归档版本：写入元信息 + 当前基线全量快照，并按 5 份限额清理最老的快照。
     *
     * <p>必须在<b>基线写入完成之后</b>调用：快照取的是「改动之后」的基线。</p>
     *
     * @param orgId      组织；{@code ""} = 基础层
     * @param type       术语类型
     * @param entries    合并后的基线全量词条
     * @param proposalId 来源提案；管理员直写为 null
     * @param operator   合并人（审核通过的组长 / 直写的管理员）
     * @param comment    备注
     * @return 新建的归档版本号
     */
    @Transactional(rollbackFor = Exception.class)
    public Integer archive(String orgId, String type, List<TermEntry> entries,
                           String proposalId, String operator, String comment) {
        // 空词典也要能归档：调用方可能拿到 null（该组织该类目一条词都没有的历史状态），
        // 若直接 entries.size()/for-each 就是空指针 —— 导入成功却报系统异常的另一种成因。
        if (entries == null) {
            entries = List.of();
        }
        String org = norm(orgId);
        int nextNo = nextVersionNo(org, type);

        DictArchiveVersion v = new DictArchiveVersion();
        v.setId(newId());
        v.setOrgId(org);
        v.setType(type);
        v.setVersionNo(nextNo);
        v.setProposalId(proposalId);
        v.setMergeTime(LocalDateTime.now().withNano(0));
        v.setMergeUserId(operator);
        v.setComment(comment);
        versionMapper.insert(v);

        for (TermEntry e : entries) {
            DictArchiveTerm t = new DictArchiveTerm();
            t.setId(newId());
            t.setVersionId(v.getId());
            t.setStandardTerm(e.getStandardTerm());
            t.setCode(e.getCode());
            t.setSource(e.getSource());
            t.setAliases(writeJson(e.getAliases()));
            termMapper.insert(t);
        }

        pruneSnapshots(org, type);
        log.info("[词典归档] org='{}' type={} 生成版本 v{}（{} 条），保留快照上限 {}",
                org, type, nextNo, entries.size(), MAX_SNAPSHOT_VERSIONS);
        return nextNo;
    }

    /**
     * 归档列表（版本倒序），含「快照是否还在」与词条数。
     *
     * @param orgId 组织；{@code ""} = 基础层
     * @param type  术语类型
     * @return 版本列表（不含已清理快照的内容，但元信息齐全）
     */
    public List<DictArchiveVersion> list(String orgId, String type) {
        String org = norm(orgId);
        List<DictArchiveVersion> rows = versionMapper.selectList(
                new QueryWrapper<DictArchiveVersion>()
                        .eq("org_id", org).eq("type", type)
                        .orderByDesc("version_no"));
        for (DictArchiveVersion v : rows) {
            Long n = termMapper.selectCount(new QueryWrapper<DictArchiveTerm>()
                    .eq("version_id", v.getId()));
            v.setTermCount(n.intValue());
            v.setSnapshotPresent(n > 0);
        }
        return rows;
    }

    /**
     * 读某归档版本的快照词条；快照已被限额清理时返回 null（调用方据此拒绝回滚）。
     *
     * @param orgId     组织
     * @param type      术语类型
     * @param versionNo 版本号
     * @return 快照词条；不存在或快照已清理返回 null
     */
    public List<TermEntry> readSnapshot(String orgId, String type, int versionNo) {
        String org = norm(orgId);
        DictArchiveVersion v = findVersion(org, type, versionNo);
        if (v == null) {
            return null;
        }
        List<DictArchiveTerm> rows = termMapper.selectList(new QueryWrapper<DictArchiveTerm>()
                .eq("version_id", v.getId()));
        if (rows.isEmpty()) {
            return null;
        }
        List<TermEntry> out = new ArrayList<>(rows.size());
        for (DictArchiveTerm t : rows) {
            TermEntry e = new TermEntry();
            e.setStandardTerm(t.getStandardTerm());
            e.setCode(t.getCode());
            e.setSource(t.getSource() == null ? "" : t.getSource());
            e.setAliases(readJson(t.getAliases()));
            out.add(e);
        }
        return out;
    }

    /** 取某版本元信息；不存在返回 null */
    public DictArchiveVersion findVersion(String orgId, String type, int versionNo) {
        return versionMapper.selectOne(new QueryWrapper<DictArchiveVersion>()
                .eq("org_id", norm(orgId)).eq("type", type).eq("version_no", versionNo)
                .last("LIMIT 1"));
    }

    /** 下一个版本号 = MAX(version_no) + 1；没有记录时为 1 */
    private int nextVersionNo(String org, String type) {
        List<DictArchiveVersion> rows = versionMapper.selectList(
                new QueryWrapper<DictArchiveVersion>()
                        .select("MAX(version_no) AS maxNo")
                        .eq("org_id", org).eq("type", type));
        // ⚠️ 必须同时判「元素本身为 null」：实测线上报错正是
        //    Cannot invoke "DictArchiveVersion.getVersionNo()" because the return value
        //    of "java.util.List.get(int)" is null —— 列表非空但首元素是 null 时，
        //    原来那句 `rows.get(0).getVersionNo()` 直接空指针，导入因此被报成系统异常
        //    （词条其实已经导入成功，只是归档没生成）。
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).getVersionNo() == null) {
            return 1;
        }
        return rows.get(0).getVersionNo() + 1;
    }

    /**
     * 按 5 份限额清理最老的<b>快照</b>；元信息一律保留。
     *
     * <p>做法：把保留窗口外的版本 id 查出来，只删它们的 dict_archive_term。
     * 刻意不删 dict_archive_version —— 删了版本号就会断档，审计链失效。</p>
     */
    private void pruneSnapshots(String org, String type) {
        List<DictArchiveVersion> all = versionMapper.selectList(
                new QueryWrapper<DictArchiveVersion>()
                        .select("id", "version_no")
                        .eq("org_id", org).eq("type", type)
                        .orderByDesc("version_no"));
        if (all.size() <= MAX_SNAPSHOT_VERSIONS) {
            return;
        }
        List<String> staleIds = new ArrayList<>();
        for (int i = MAX_SNAPSHOT_VERSIONS; i < all.size(); i++) {
            staleIds.add(all.get(i).getId());
        }
        int deleted = termMapper.delete(new QueryWrapper<DictArchiveTerm>()
                .in("version_id", staleIds));
        log.info("[词典归档] org='{}' type={} 超出 {} 份限额，已清理 {} 条最老快照（元信息保留）",
                org, type, MAX_SNAPSHOT_VERSIONS, deleted);
    }

    private String norm(String orgId) {
        return orgId == null ? "" : orgId;
    }

    private String newId() {
        return java.util.UUID.randomUUID().toString();
    }

    private String writeJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
