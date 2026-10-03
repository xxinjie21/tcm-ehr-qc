package com.tcm.ehr.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.exception.BusinessException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.domain.po.DictProposal;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictProposalDiffVO;
import com.tcm.ehr.mapper.DictArchiveTermMapper;
import com.tcm.ehr.mapper.DictArchiveVersionMapper;
import com.tcm.ehr.mapper.DictProposalMapper;
import com.tcm.ehr.mapper.DictProposalTermMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提案域的关键契约（批次 17 第 3 步）。
 *
 * <p>重点锁三件容易出错、且出错后<b>不报错</b>的事：<br>
 * ① 合并后必须调用 {@code markIndexed} —— 漏了启动对账会认为已同步，归一静默用旧数据；<br>
 * ② 待审上限必须先查再建 —— 顺序反了就是「先写完快照再发现有超限」；<br>
 * ③ 回滚必须<b>生成提案</b>而不是直接改基线 —— 直接改就绕过了审核。</p>
 */
class DictProposalServiceTest {

    private DictProposalMapper proposalMapper;
    private DictProposalTermMapper termMapper;
    private DictionaryTermStore termStore;
    private DictArchiveService archiveService;
    private IEsTermIndexService esIndexService;
    private DistLock distLock;
    private DictProposalService svc;

    @BeforeEach
    void setUp() {
        proposalMapper = mock(DictProposalMapper.class);
        termMapper = mock(DictProposalTermMapper.class);
        termStore = mock(DictionaryTermStore.class);
        archiveService = mock(DictArchiveService.class);
        esIndexService = mock(IEsTermIndexService.class);
        // 锁：直接执行临界区（本类测的是「临界区里做了什么」，互斥本身由 DistLockTest 负责）
        distLock = mock(DistLock.class);
        when(distLock.runLocked(anyString(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> body =
                    (java.util.function.Supplier<Object>) inv.getArgument(1);
            return body.get();
        });

        when(termStore.replace(anyString(), anyString(), any())).thenReturn("v-new");
        svc = new DictProposalService(proposalMapper, termMapper, termStore,
                archiveService, esIndexService, new tools.jackson.databind.ObjectMapper(), distLock);
    }

    private static List<TermEntry> terms(String... names) {
        List<TermEntry> out = new ArrayList<>();
        for (String n : names) {
            out.add(new TermEntry(n, List.of(), "测试"));
        }
        return out;
    }

    private static DictProposal proposal(String status) {
        DictProposal p = new DictProposal();
        p.setId("p1");
        p.setOrgId("org-A");
        p.setType("herb");
        p.setSubmitUserId("alice");
        p.setStatus(status);
        return p;
    }

    /** 快照读出来就是提交时那几条（source 与基线 fixture 保持一致，否则 diff 会判为「修改」） */
    private void snapshotContains(String... names) {
        List<com.tcm.ehr.domain.po.DictProposalTerm> rows = new ArrayList<>();
        for (String n : names) {
            com.tcm.ehr.domain.po.DictProposalTerm t = new com.tcm.ehr.domain.po.DictProposalTerm();
            t.setProposalId("p1");
            t.setStandardTerm(n);
            t.setSource("测试");
            t.setAliases("[]");
            rows.add(t);
        }
        when(termMapper.selectList(any())).thenReturn(rows);
        when(termMapper.selectCount(any())).thenReturn((long) rows.size());
    }

    // ------------------------------------------------------------ 待审上限

    @Test
    @DisplayName("待审已达 5 条：拒绝提交，且不写任何快照")
    void rejectsWhenPendingLimitReached() {
        when(proposalMapper.selectCount(any())).thenReturn(5L);

        assertThrows(BusinessException.class,
                () -> svc.submit("org-A", "herb", terms("甘草"), "bob"));

        // 上限的意义就是别把快照表撑大：超限时必须一行都没写
        verify(proposalMapper, never()).insert(any(DictProposal.class));
        verify(termMapper, never()).insert(any(com.tcm.ehr.domain.po.DictProposalTerm.class));
    }

    @Test
    @DisplayName("待审未满：正常建提案并写快照")
    void createsProposalUnderLimit() {
        when(proposalMapper.selectCount(any())).thenReturn(4L);
        when(proposalMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(termMapper.selectCount(any())).thenReturn(0L);

        var vo = svc.submit("org-A", "herb", terms("甘草", "人参"), "bob");

        assertEquals(2, vo.getTermCount());
        verify(proposalMapper).insert(any(DictProposal.class));
        verify(termMapper, times(2)).insert(any(com.tcm.ehr.domain.po.DictProposalTerm.class));
    }

    // ------------------------------------------------------------ 审核合并

    @Test
    @DisplayName("审核通过：整快照替换基线 + 重建 ES + markIndexed + 归档，顺序缺一不可")
    void approveMergesAndReconcilesEs() throws Exception {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));
        snapshotContains("甘草", "人参");
        when(termStore.read(anyString(), anyString())).thenReturn(terms("旧词"));

        var vo = svc.audit("p1", true, "同意", "owner-1", true);

        // 1) 基线被替换
        verify(termStore).replace(eq("org-A"), eq("herb"), any());
        // 2) ES 重建
        verify(esIndexService).rebuild(eq("herb"), eq("org-A"), any(), eq("v-new"));
        // 3) ★ markIndexed：漏了启动对账会判「已同步」，归一静默用旧数据
        verify(termStore).markIndexed(eq("org-A"), eq("herb"), eq("v-new"));
        // 4) 归档（提案合并后必须留快照，否则回滚无源）
        verify(archiveService).archive(eq("org-A"), eq("herb"), any(),
                eq("p1"), eq("owner-1"), eq("同意"));
        assertEquals(DictProposal.APPROVED, vo.getStatus());
    }

    @Test
    @DisplayName("审核通过时走跨实例锁（与导入/重建互斥，避免交错出混合索引）")
    void approveTakesRebuildLock() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));
        snapshotContains("甘草");
        when(termStore.read(anyString(), anyString())).thenReturn(List.of());

        svc.audit("p1", true, null, "owner-1", true);

        verify(distLock).runLocked(eq(DistLock.dictRebuildLock("herb", "org-A")), any());
    }

    @Test
    @DisplayName("重复审核已处理的提案：拒绝，不产生第二次合并与第二份归档")
    void refusesDoubleAudit() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.APPROVED));

        assertThrows(BusinessException.class, () -> svc.audit("p1", true, null, "owner-1", true));

        verify(termStore, never()).replace(anyString(), anyString(), any());
        verify(archiveService, never()).archive(anyString(), anyString(), any(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("非组长审核：拒绝")
    void nonOwnerCannotAudit() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));

        assertThrows(ForbiddenException.class, () -> svc.audit("p1", true, null, "member", false));
    }

    @Test
    @DisplayName("驳回：填理由 + 设 7 天清理时间，且不碰基线")
    void rejectDoesNotTouchBaseline() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));

        var vo = svc.audit("p1", false, "术语不准确", "owner-1", true);

        assertEquals(DictProposal.REJECTED, vo.getStatus());
        assertTrue(vo.getPurgeAfter() != null, "驳回应安排快照清理时间");
        verify(termStore, never()).replace(anyString(), anyString(), any());
        verify(archiveService, never()).archive(anyString(), anyString(), any(),
                anyString(), anyString(), any());
    }

    // ------------------------------------------------------------ 编辑提案

    @Test
    @DisplayName("只能编辑自己提交的提案")
    void onlySubmitterCanEdit() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));

        assertThrows(ForbiddenException.class,
                () -> svc.editTerms("p1", terms("甘草"), "someone-else"));
    }

    @Test
    @DisplayName("已通过的提案不能再编辑")
    void approvedProposalNotEditable() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.APPROVED));

        assertThrows(BusinessException.class,
                () -> svc.editTerms("p1", terms("甘草"), "alice"));
    }

    // ------------------------------------------------------------ 差异计算

    @Test
    @DisplayName("差异：识别新增 / 修改 / 删除（删除靠整快照替换才生效）")
    void diffClassifiesAddModifyRemove() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));
        // 提案含：甘草(新)、人参(改)、当归(基线有、提案没有)
        snapshotContains("甘草", "人参");
        TermEntry baselineRen = new TermEntry("人参", List.of("上党人参"), "测试");
        when(termStore.read("org-A", "herb")).thenReturn(
                List.of(baselineRen, new TermEntry("当归", List.of(), "测试")));

        DictProposalDiffVO d = svc.diff("p1");

        assertEquals(List.of("甘草"), d.getAdded().stream()
                .map(m -> m.get("standardTerm")).toList());
        assertEquals(1, d.getModified().size());
        assertEquals(List.of("当归"), d.getRemoved());
        assertFalse(d.isEmptyDiff());
    }

    @Test
    @DisplayName("差异：与基线完全一致时 noDiff=true（前端应提示「无需变更」）")
    void diffNoChange() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));
        snapshotContains("甘草");
        when(termStore.read("org-A", "herb")).thenReturn(terms("甘草"));

        DictProposalDiffVO d = svc.diff("p1");

        assertTrue(d.getNoDiff());
        assertTrue(d.isEmptyDiff());
    }

    @Test
    @DisplayName("差异：只改别名也算「修改」，不能被当成没变")
    void aliasChangeCountsAsModified() {
        when(proposalMapper.selectById("p1")).thenReturn(proposal(DictProposal.PENDING));
        snapshotContains("甘草");
        TermEntry base = new TermEntry("甘草", List.of("国老"), "测试");
        when(termStore.read("org-A", "herb")).thenReturn(List.of(base));

        DictProposalDiffVO d = svc.diff("p1");

        assertEquals(1, d.getModified().size(),
                "别名变化必须算修改；漏判会让组长以为「无变更」直接合并，丢失本次编辑");
    }

    // ------------------------------------------------------------ 回滚

    @Test
    @DisplayName("回滚基于归档版本生成【新提案】，不直接改基线（否则绕过审核）")
    void rollbackGeneratesProposalNotDirectMerge() {
        when(archiveService.readSnapshot("org-A", "herb", 3)).thenReturn(terms("旧词"));
        when(proposalMapper.selectCount(any())).thenReturn(0L);
        when(proposalMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(termMapper.selectCount(any())).thenReturn(0L);

        svc.rollbackTo("org-A", "herb", 3, "owner-1");

        verify(proposalMapper).insert(any(DictProposal.class));
        verify(termStore, never()).replace(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("回滚：快照已被 5 份限额清理时拒绝，并说明只剩元信息")
    void rollbackFailsWhenSnapshotPruned() {
        when(archiveService.readSnapshot("org-A", "herb", 1)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> svc.rollbackTo("org-A", "herb", 1, "owner-1"));
        assertTrue(e.getMessage().contains("已被清理"), e.getMessage());
    }

    // ------------------------------------------------------------ 惰性清理

    @Test
    @DisplayName("惰性清理：只删过期快照，提案主记录一条不动")
    void lazyPurgeDeletesOnlySnapshots() {
        DictProposal expired = proposal(DictProposal.REJECTED);
        expired.setPurgeAfter(java.time.LocalDateTime.now().minusDays(1));
        // 第 1 次 selectList 是惰性清理（返回过期提案），第 2 次是列表查询（返回空）
        when(proposalMapper.selectList(any()))
                .thenReturn(List.of(expired))
                .thenReturn(new ArrayList<>());
        when(termMapper.delete(any())).thenReturn(7);
        when(termMapper.selectCount(any())).thenReturn(0L);

        svc.list("org-A", null, true, "owner-1");

        // 只对 dict_proposal_term 删；proposal 主记录既不删也不按 id 删
        verify(termMapper).delete(any());
        verify(proposalMapper, never()).delete(any());
        verify(proposalMapper, never()).deleteById(any(String.class));
    }
}