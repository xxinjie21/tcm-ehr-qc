package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.exception.BusinessException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.service.IDictArchiveService;
import com.tcm.ehr.service.IDictProposalService;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IEsTermIndexService;
import com.tcm.ehr.domain.dto.DictProposalDTOs;
import com.tcm.ehr.domain.po.DictProposal;
import com.tcm.ehr.domain.po.DictProposalTerm;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictProposalDiffVO;
import com.tcm.ehr.domain.vo.DictProposalVO;
import com.tcm.ehr.mapper.DictProposalMapper;
import com.tcm.ehr.mapper.DictProposalTermMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 词典基线更新提案（批次 17）。
 *
 *
 * 成员不能直接改小组基线，只能提交一份「完整目标词典」提案；组长审核通过后才合并进
 *
 * dictionary_terms 并生成归档版本。
 *
 *
 * 为什么提案存全量而不是增量 diff：合并是整快照替换（这样才能支持删除），
 *
 * 组长审核时在快照上直接增删改即可，不必在合并那一刻再算一次差异、也不必处理
 * 「diff 与快照对不上」这种中间态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictProposalServiceImpl implements IDictProposalService {

    /** 同一 (org,type) 最多同时存在的待审提案数 */
    public static final int MAX_PENDING = 5;

    /** 被驳回后快照的保留天数（到期后由惰性清理删除快照，主记录永久保留） */
    public static final int REJECTED_RETENTION_DAYS = 7;

    private final DictProposalMapper proposalMapper;
    private final DictProposalTermMapper termMapper;
    private final IDictionaryTermStore termStore;
    private final IDictArchiveService archiveService;
    private final IEsTermIndexService esIndexService;
    private final ObjectMapper objectMapper;
    private final DistLock distLock;
    /** 词典写权限（批次 6 工作项 4）：授权位在 organization_members 里，不能只看 JWT 的 role */
    private final com.tcm.ehr.service.IOrgPermissionService orgPermission;

    // ---------------------------------------------------------------- 基线导出

    /**
     * 导出「小组基线」给前端存本地个人词典。
     *
     * @param orgId 组织；"" = 基础层
     * @param type  术语类型
     * @return 该层完整词条（与 read 一致，不是 effective：个人词典要能看出
     *         「本组织没有自有词条」，否则用户会以为拉到的是空的）
     */
    public List<TermEntry> exportBaseline(String orgId, String type) {
        return termStore.read(orgId, type);
    }

// ---------------------------------------------------------------- 提交提案

/**
 * 提交提案时的规模下限比例：快照小于基线这个比例即拒绝。
 *
 * 提案携带的是**完整目标词典**，审核通过走整快照替换（见 audit）。
 * 所以「快照比基线小很多」等于「一提交就会删掉基线里大量词条」——
 * 典型触发路径：成员没点「拉取小组基线」，直接上传一个自己整理的小文件当提案，
 * 组长没细看差异就点通过，该类型的基线就被清空了。
 */
static final int MIN_SHRINK_RATIO_PERCENT = 80;

/**
 * 提交提案（先校验待审上限，再校验规模下限）。
 *
 *
 * 上限的作用是防止反复提交把 dict_proposal_term 撑爆：每个提案都带一份
 *
 * 全量快照（疾病类可上千条），无上限时刷 100 次就是十万行。
 *
 * @throws BusinessException 已有 5 条待审提案，或快照相对基线缩水过多
 */
@Transactional(rollbackFor = Exception.class)
public DictProposalVO submit(String orgId, String type, List<TermEntry> terms, String submitUserId) {
        String org = norm(orgId);
        // M1（2026-10-05）：**「没有组织」不等于「要写基础层」** —— norm 把空 orgId 归一成 ""，
        // 而 "" 正是基础层（org_id=''）的约定，于是无组织用户的提案会落进各组织共用的系统基线。
        // 原先只在**审核**环节拦（requireAuditable：「基础层提案仅管理员可审核」），
        // 提交环节没拦：提案能建出来、只是合不进去 —— 这正是报告 M1 说的「无组用户可向基础层提交提案」。
        // 这里与 /import?target=base、审核处保持**同一口径**：基础层只允许管理员。
        if (org.isEmpty() && !RequestUtils.isAdmin()) {
            throw new ForbiddenException("基础层提案仅管理员可提交");
        }
        // 1. 先查上限再建快照 —— 顺序反了就是「先写完再发现有超限」
        Long pending = proposalMapper.selectCount(new QueryWrapper<DictProposal>()
                .eq("org_id", org).eq("type", type).eq("status", DictProposal.PENDING));
        if (pending != null && pending >= MAX_PENDING) {
            throw new BusinessException(4003, "该组织该类型已有 " + MAX_PENDING + " 条待审提案，请等待组长处理后再提交");
        }
        // 2. 规模下限：提案是整快照替换，快照明显缩水等于「一提交就要删基线」
        checkNotShrunk(org, type, terms);
        purgeExpiredSnapshots();

        DictProposal p = new DictProposal();
        p.setId(newId());
        p.setOrgId(org);
        p.setType(type);
        p.setSubmitUserId(submitUserId);
        p.setStatus(DictProposal.PENDING);
        p.setCreateTime(LocalDateTime.now().withNano(0));
        proposalMapper.insert(p);
        replaceSnapshot(p.getId(), terms);
        log.info("[词典提案] {} 提交提案 org='{}' type={}，{} 条待审",
                submitUserId, org, type, terms.size());
        return toVO(p, terms.size());
    }

    // ---------------------------------------------------------------- 列表 / 详情

/**
 * 提案列表（顺带做惰性清理）。
 *
 * 过滤维度收在 ProposalQuery 里：组织、状态、类型、视角、提交人。
 * type 为空则不限类型，供管理员跨类型查看。
 *
 * @param query 查询条件，调用方负责填好视角与提交人
 * @return 提案视图列表，按提交时间倒序，最多 200 条
 */
public List<DictProposalVO> list(DictProposalDTOs.ProposalQuery query) {
        purgeExpiredSnapshots();
        QueryWrapper<DictProposal> q = new QueryWrapper<>();
        // 组织条件必填：以前 orgId 为 null 时不加条件，组长便能看到全库提案
        // （含别组提案的完整词条快照）。要「本组 + 基础层」必须用括号把 OR 包起来，
        // 否则 OR 会与后面的状态/类型条件平级，把过滤条件整个失效
        if (query.isIncludeBaseLayer()) {
            q.and(w -> w.eq("org_id", query.getOrgId()).or().eq("org_id", ""));
        } else {
            q.eq("org_id", query.getOrgId());
        }
        if (notBlank(query.getStatus())) {
            q.eq("status", query.getStatus());
        }
        if (notBlank(query.getType())) {
            q.eq("type", query.getType());
        }
        if (query.filterBySubmitter()) {
            // 成员只看自己提交的提案：否则能翻出别人的提交内容
            q.eq("submit_user_id", query.getSubmitUserId());
        }
        q.orderByDesc("create_time").last("LIMIT 200");
        List<DictProposal> rows = proposalMapper.selectList(q);
        List<DictProposalVO> out = new ArrayList<>(rows.size());
        for (DictProposal p : rows) {
            out.add(toVO(p, snapshotCount(p.getId())));
        }
        return out;
    }

    /** 取提案；不存在返回 null */
    public DictProposal get(String proposalId) {
        return proposalMapper.selectById(proposalId);
    }

    /**
     * 审核权限：管理员一律可以；基础层提案（org_id 为空串）只允许管理员；
     * 其余要求「提案属于当前组织」且「当前用户是所有者或被授予词典写权限」。
     *
     * 为什么必须按提案自身的 org_id 判，而不是看调用方传来的 isOwner 布尔：isOwner 只说明
     * 「我在自己组织里是 owner」，对任何组织的 owner 都为真，与目标提案属于哪个组织无关
     * （2026-10-05 之前就是这么漏的）。批次 6 工作项 4 起另加授权位判定，
     * 于是本方法与 {@code QcController.requireRuleWrite} 同一口径。
     *
     * @param p 提案行
     * @throws ForbiddenException 无权审核时抛出，消息按原因区分
     */
    private void requireAuditable(DictProposal p) {
        if (RequestUtils.isAdmin()) {
            return;
        }
        String proposalOrg = p.getOrgId();
        if (proposalOrg == null || proposalOrg.isBlank()) {
            throw new ForbiddenException("基础层提案仅管理员可审核");
        }
        String currentOrgId = RequestUtils.currentOrgId();
        if (currentOrgId == null || currentOrgId.isBlank() || !proposalOrg.equals(currentOrgId)) {
            throw new ForbiddenException("无权审核其它课题组的提案");
        }
        // 授权位按「主组织」查，故只在确认提案属于本组织之后才用它 ——
        // 否则「在 A 组被授权」会等价于「可审 A 组以外的提案」
        if (!orgPermission.canWriteDictionary(RequestUtils.currentUserId())) {
            throw new ForbiddenException("需管理员、组织所有者或被授权成员才能审核提案");
        }
    }

    // ---------------------------------------------------------------- 差异

    /**
     * 实时算提案与当前基线的差异（不落表）。
     *
     * @param proposalId 提案
     * @return 新增 / 修改 / 删除三组；两组都空时 noDiff=true
     */
    public DictProposalDiffVO diff(String proposalId) {
        purgeExpiredSnapshots();
        DictProposal p = proposalMapper.selectById(proposalId);
        if (p == null) {
            throw new BusinessException(4003, "提案不存在");
        }
        List<TermEntry> snapshot = readSnapshot(proposalId);
        List<TermEntry> baseline = termStore.read(p.getOrgId(), p.getType());

        Map<String, TermEntry> baseMap = new LinkedHashMap<>();
        for (TermEntry e : baseline) {
            baseMap.put(key(e.getStandardTerm()), e);
        }
        Set<String> snapKeys = new LinkedHashSet<>();

        DictProposalDiffVO vo = new DictProposalDiffVO();
        for (TermEntry e : snapshot) {
            String k = key(e.getStandardTerm());
            snapKeys.add(k);
            TermEntry base = baseMap.get(k);
            if (base == null) {
                vo.getAdded().add(toMap(e));
            } else if (changed(base, e)) {
                Map<String, Object> row = toMap(e);
                // 带上旧值，前端才能显示「从 A 改成 B」
                row.put("before", toMap(base));
                vo.getModified().add(row);
            }
        }
        for (TermEntry e : baseline) {
            if (!snapKeys.contains(key(e.getStandardTerm()))) {
                vo.getRemoved().add(e.getStandardTerm());
            }
        }
        vo.setNoDiff(vo.isEmptyDiff());
        return vo;
    }

    // ---------------------------------------------------------------- 在线编辑提案

    /**
     * 在线修改提案内容（只影响提案，不动基线）。
     *
     * @param proposalId 提案
     * @param terms      新的完整目标词典
     * @param operator   操作人；必须是提交者本人
     */
    @Transactional(rollbackFor = Exception.class)
    public void editTerms(String proposalId, List<TermEntry> terms, String operator) {
        DictProposal p = requireEditable(proposalId, operator);
        replaceSnapshot(p.getId(), terms);
        log.info("[词典提案] {} 编辑提案内容，{} 条", operator, terms.size());
    }

    // ---------------------------------------------------------------- 审核

    /**
     * 审核提案：通过则合并进基线并生成归档版本；拒绝则标记作废并设置快照清理时间。
     *
     *
     * 合并这一段是最需要小心的：基线写入 → ES 重建 → markIndexed → 归档。
     *
     * 其中 markIndexed 绝不能漏，漏了启动对账会认为已同步，归一就静默用旧数据
     * （批次 8b 的 aliases_json 与 ES 映射不兼容就是这么排查出来的）。
     *
     * @param approve true=通过并合并
     */
    @Transactional(rollbackFor = Exception.class)
    public DictProposalVO audit(String proposalId, boolean approve, String comment,
                                String auditor) {
        purgeExpiredSnapshots();
        DictProposal p = proposalMapper.selectById(proposalId);
        if (p == null) {
            throw new BusinessException(4003, "提案不存在");
        }
        // 权限与归属一起判（批次 6 工作项 4）：合并会整份替换目标组织的基线并重建其 ES 索引，
        // 属跨机构写。原实现只看调用方传来的 isOwner（恒为真），等于没判
        requireAuditable(p);
        if (!p.isPending()) {
            // 重复提交审核：直接返回当前状态，不二次合并（否则会生成两份归档）
            throw new BusinessException(4003, "该提案已" + statusText(p.getStatus()) + "，不能重复审核");
        }

        if (!approve) {
            p.setStatus(DictProposal.REJECTED);
            p.setAuditUserId(auditor);
            p.setAuditComment(comment);
            p.setAuditTime(LocalDateTime.now().withNano(0));
            p.setPurgeAfter(LocalDateTime.now().plusDays(REJECTED_RETENTION_DAYS).withNano(0));
            proposalMapper.updateById(p);
            log.info("[词典提案] {} 驳回提案 {}：{}", auditor, proposalId, comment);
            return toVO(p, snapshotCount(proposalId));
        }

        List<TermEntry> terms = readSnapshot(proposalId);
        if (terms == null || terms.isEmpty()) {
            throw new BusinessException(4003, "提案快照已被清理，无法合并；请让提交者重新提交");
        }

        // 合并走与导入/重建同一把跨实例锁：避免与并发导入交错出混合索引
        distLock.runLocked(DistLock.dictRebuildLock(p.getType(), p.getOrgId()), () -> {
            String version = termStore.replace(p.getOrgId(), p.getType(), terms);
            try {
                esIndexService.rebuild(p.getType(), p.getOrgId(), terms, version);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // 灌成功才记已同步
            termStore.markIndexed(p.getOrgId(), p.getType(), version);
            // 归档取「合并后」的基线：传 replace 后的版本号无关，这里直接用 terms
            archiveService.archive(p.getOrgId(), p.getType(), terms,
                    p.getId(), auditor, comment);
            return null;
        });

        p.setStatus(DictProposal.APPROVED);
        p.setAuditUserId(auditor);
        p.setAuditComment(comment);
        p.setAuditTime(LocalDateTime.now().withNano(0));
        proposalMapper.updateById(p);
        log.info("[词典提案] {} 审核通过并合并提案 {}（{} 条），已生成归档版本",
                auditor, proposalId, terms.size());
        return toVO(p, terms.size());
    }

    // ---------------------------------------------------------------- 回滚（生成提案）

    /**
     * 基于历史归档版本生成一份新提案（不直接还原基线）。
     *
     *
     * 走提案是有意的：回滚若直接改基线，就绕过了审核，与「任何基线变更都要过组长」
     *
     * 的约定冲突。
     *
     * @return 新提案的 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public DictProposalVO rollbackTo(String orgId, String type, int versionNo, String operator) {
        List<TermEntry> snapshot = archiveService.readSnapshot(orgId, type, versionNo);
        if (snapshot == null) {
            throw new BusinessException(4003, "该版本的归档快照已被清理（仅保留版本元信息），无法回滚");
        }
        DictProposalVO vo = submit(orgId, type, snapshot, operator);
        log.info("[词典提案] {} 基于归档 v{} 生成回滚提案 {}（{} 条）",
                operator, versionNo, vo.getId(), snapshot.size());
        return vo;
    }

    // ---------------------------------------------------------------- 惰性清理

    /**
     * 惰性清理过期快照：只删 dict_proposal_term，提案主记录永久保留。
     *
     *
     * 刻意不引入定时任务（与计划附录 B.1「不做定时任务/自动清理」的既有决议一致），
     *
     * 由「提交提案 / 查列表 / 看详情」三个入口顺带触发。
     */
    private void purgeExpiredSnapshots() {
        List<DictProposal> expired = proposalMapper.selectList(new QueryWrapper<DictProposal>()
                .select("id")
                .eq("status", DictProposal.REJECTED)
                .isNotNull("purge_after")
                .lt("purge_after", LocalDateTime.now())
                .last("LIMIT 200"));
        if (expired.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>(expired.size());
        for (DictProposal p : expired) {
            ids.add(p.getId());
        }
        int deleted = termMapper.delete(new QueryWrapper<DictProposalTerm>().in("proposal_id", ids));
        log.info("[词典提案] 惰性清理过期快照：{} 条提案 / {} 行快照（主记录保留）", ids.size(), deleted);
    }

    // ---------------------------------------------------------------- 内部

    /** 要求提案处于可编辑状态，且操作人是提交者本人 */
    private DictProposal requireEditable(String proposalId, String operator) {
        DictProposal p = proposalMapper.selectById(proposalId);
        if (p == null) {
            throw new BusinessException(4003, "提案不存在");
        }
        if (!p.isPending()) {
            throw new BusinessException(4003, "只有待审提案可以编辑，当前状态：" + statusText(p.getStatus()));
        }
        if (!p.getSubmitUserId().equals(operator)) {
            throw new ForbiddenException("只能编辑自己提交的提案");
        }
        return p;
    }

    /** 用新词条整体替换某提案的快照 */
    private void replaceSnapshot(String proposalId, List<TermEntry> terms) {
        termMapper.delete(new QueryWrapper<DictProposalTerm>().eq("proposal_id", proposalId));
        for (TermEntry e : terms) {
            if (e.getStandardTerm() == null || e.getStandardTerm().isBlank()) {
                continue;
            }
            DictProposalTerm t = new DictProposalTerm();
            t.setId(newId());
            t.setProposalId(proposalId);
            t.setStandardTerm(e.getStandardTerm().trim());
            t.setCode(e.getCode());
            t.setSource(e.getSource() == null ? "" : e.getSource());
            t.setAliases(writeJson(e.getAliases()));
            termMapper.insert(t);
        }
    }

    /** 读某提案的快照词条 */
    public List<TermEntry> readSnapshot(String proposalId) {
        List<DictProposalTerm> rows = termMapper.selectList(new QueryWrapper<DictProposalTerm>()
                .eq("proposal_id", proposalId));
        List<TermEntry> out = new ArrayList<>(rows.size());
        for (DictProposalTerm t : rows) {
            TermEntry e = new TermEntry();
            e.setStandardTerm(t.getStandardTerm());
            e.setCode(t.getCode());
            e.setSource(t.getSource() == null ? "" : t.getSource());
            e.setAliases(readJson(t.getAliases()));
            out.add(e);
        }
        return out;
    }

    /** 快照行数 */
    public int snapshotCount(String proposalId) {
        Long n = termMapper.selectCount(new QueryWrapper<DictProposalTerm>().eq("proposal_id", proposalId));
        return n == null ? 0 : n.intValue();
    }

    private DictProposalVO toVO(DictProposal p, int termCount) {
        DictProposalVO vo = new DictProposalVO();
        vo.setId(p.getId());
        vo.setOrgId(p.getOrgId());
        vo.setType(p.getType());
        vo.setSubmitUserId(p.getSubmitUserId());
        vo.setStatus(p.getStatus());
        vo.setAuditUserId(p.getAuditUserId());
        vo.setAuditComment(p.getAuditComment());
        vo.setCreateTime(p.getCreateTime());
        vo.setAuditTime(p.getAuditTime());
        vo.setPurgeAfter(p.getPurgeAfter());
        vo.setTermCount(termCount);
        vo.setSnapshotPresent(termCount > 0);
        return vo;
    }

    /** 比对键：标准词去空白 + 转小写，避免「胃痛」与「 胃痛 」被判成两条 */
    private String key(String standardTerm) {
        return standardTerm == null ? "" : standardTerm.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 两条例外同标准词是否内容不同（code / source / aliases 任意一项不同即算修改） */
    private boolean changed(TermEntry a, TermEntry b) {
        if (!eq(a.getCode(), b.getCode()) || !eq(a.getSource(), b.getSource())) {
            return true;
        }
        List<String> x = a.getAliases() == null ? List.of() : a.getAliases();
        List<String> y = b.getAliases() == null ? List.of() : b.getAliases();
        return !new LinkedHashSet<>(x).equals(new LinkedHashSet<>(y));
    }

    private boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private Map<String, Object> toMap(TermEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("standardTerm", e.getStandardTerm());
        m.put("code", e.getCode());
        m.put("source", e.getSource());
        m.put("aliases", e.getAliases());
        return m;
    }

    private String statusText(String s) {
        if (DictProposal.PENDING.equals(s)) {
            return "待审核";
        }
        if (DictProposal.APPROVED.equals(s)) {
            return "已通过";
        }
        if (DictProposal.REJECTED.equals(s)) {
            return "已拒绝";
        }
        return s;
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

    /** 查询条件里 null 与空白一视同仁：都表示「这一维度不过滤」 */
    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 规模下限校验：快照条数不得明显少于当前基线。
     *
     *
     * 为什么必须在提交侧拦，而不是在审核侧提醒：审核通过是整快照替换，
     *
     * 到那一步基线已经被覆盖了，只能靠归档回滚补救；提交侧拦是唯一还来得及的时机。
     *
     *
     * 为什么用比例而不是绝对条数：不同类型的基线规模差一个数量级
     *
     * （症状 72 条 vs 疾病 1364 条），写死条数会对小词典误报、对大词典漏报。
     *
     *
     * 基线为空时一律放过：那正是「首次建库」场景，不该拦。
     *
     */
    private void checkNotShrunk(String org, String type, List<TermEntry> terms) {
        int incoming = terms == null ? 0 : terms.size();
        int baseline = termStore.read(org, type).size();
        if (baseline == 0) {
            return;
        }
        int floor = baseline * MIN_SHRINK_RATIO_PERCENT / 100;
        if (incoming < floor) {
            throw new BusinessException(4003,
                    "本次提案只有 " + incoming + " 条，而小组基线有 " + baseline
                            + " 条；提案按整份词典替换，通过后会删除基线中的 "
                            + (baseline - incoming) + " 条。"
                            + "请先在「我的词典」点「拉取小组基线」，再把你的修改合并进去后提交。");
        }
    }

    private String norm(String orgId) {
        return orgId == null ? "" : orgId;
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }
}
