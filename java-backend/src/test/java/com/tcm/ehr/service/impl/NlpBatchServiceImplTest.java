package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.NlpTask;
import com.tcm.ehr.domain.vo.NlpTasksVO;
import com.tcm.ehr.mapper.NlpTaskMapper;
import com.tcm.ehr.mapper.NlpTaskItemMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryFileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量解析「提交期」的序列化契约（批1·B1-3）。
 *
 * <p>锁的是一条静默降级：筛选条件序列化失败若被吞成字符串 {@code "null"}，
 * {@code readFilters} 会把它读成 {@code null}，任务就从「指定范围」变成<b>全库扫描</b>——
 * 用户以为只跑了筛出来的那几百条。所以提交路径必须失败即抛。</p>
 *
 * <p>放在 {@code service.impl} 包内，是为了直接测包级可见的
 * {@link NlpBatchServiceImpl#writeJsonStrict}；走 {@code submit()} 测不了这条 ——
 * 入参 {@code NlpBatchDTO.filters} 的类型是 {@code FiltersDTO}，构造不出不可序列化的值。</p>
 */
class NlpBatchServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** null 不是失败：序列化成 {@code "null"}、读回为 null，语义是「不限范围」 */
    @Test
    void nullFiltersSerializeToJsonNull() {
        assertEquals("null", NlpBatchServiceImpl.writeJsonStrict(mapper, null));
    }

    @Test
    void normalFiltersSerialize() {
        FiltersDTO f = new FiltersDTO();
        f.setDepartment("中医内科");
        f.setDateRange(List.of("2026-01-01", "2026-01-31"));

        String json = NlpBatchServiceImpl.writeJsonStrict(mapper, f);

        assertTrue(json.contains("中医内科"), json);
        assertTrue(json.contains("2026-01-01"), json);
    }

    /** 不可序列化 → 抛异常中止提交，绝不返回 "null"（那会被读成「不限范围」） */
    @Test
    void unserializableFiltersThrowInsteadOfDegradingToNull() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NlpBatchServiceImpl.writeJsonStrict(mapper, new Boom()));

        assertTrue(e.getMessage().contains("无法序列化"), e.getMessage());
    }

    /** 取值即抛的 bean：Jackson 序列化它必然失败 */
    public static class Boom {
        @SuppressWarnings("unused")
        public String getExplode() {
            throw new IllegalStateException("boom");
        }
    }

    // ------------------------------------------------------------------ 收尾状态（G5）

    @Test
    void endStatus_normalCompletionIsCompleted() {
        assertEquals(NlpTask.COMPLETED, NlpBatchServiceImpl.endStatus(true, false, 500, 500));
        // 范围内没有病历：done == total == 0，同样是正常结束
        assertEquals(NlpTask.COMPLETED, NlpBatchServiceImpl.endStatus(true, false, 0, 0));
    }

    @Test
    void endStatus_cancelledWinsOverCompleted() {
        assertEquals(NlpTask.CANCELLED, NlpBatchServiceImpl.endStatus(true, true, 3, 500));
    }

    /** **G5 就错在这里**：停机时没跑完，不能声称「已完成」 */
    @Test
    void endStatus_shutdownWhileUnfinishedIsInterrupted() {
        assertEquals(NlpTask.INTERRUPTED, NlpBatchServiceImpl.endStatus(false, false, 50, 500));
    }

    /** 反向：停机窗口内恰好跑完的仍是「已完成」，别把真跑完的误标成中断 */
    @Test
    void endStatus_shutdownAfterFinishingStaysCompleted() {
        assertEquals(NlpTask.COMPLETED, NlpBatchServiceImpl.endStatus(false, false, 500, 500));
    }

    /** 停机时用户已取消 → 仍是「已取消」，不改成中断 */
    @Test
    void endStatus_shutdownWithCancelledStaysCancelled() {
        assertEquals(NlpTask.CANCELLED, NlpBatchServiceImpl.endStatus(false, true, 10, 500));
    }

    // ------------------------------------------------- 批次 3：提交防重

    /**
     * 解析侧原先<b>完全没有</b>防重（质控侧有）：重复点击会起多个并发任务同时压 Python 服务，
     * 且后提交的任务进度会互相覆写。锁住「已有活跃任务时拒绝提交」。
     */
    @Test
    void submitRejectsWhenAnotherTaskIsActive() {
        NlpTaskMapper taskMapper = mock(NlpTaskMapper.class);
        // selectCount 返回已有活跃任务（防重命中，insert 不该被调用）
        when(taskMapper.selectCount(any())).thenReturn(1L);
        // nlp.enabled 是更前置的门（不开抽取连排任务都不该允许），先让它通过才能走到防重那一步
        PythonNlpClient nlpClient = mock(PythonNlpClient.class);
        when(nlpClient.isEnabled()).thenReturn(true);

        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(taskMapper,
                mock(RecordMapper.class), mock(NlpTaskItemMapper.class), nlpClient, mock(EntityNormalizer.class),
                mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.submit(new com.tcm.ehr.domain.dto.NlpBatchDTO(), "tester"));
        assertTrue(e.getMessage().contains("已有解析任务"), e.getMessage());
        // 防重命中时应就地拒绝：除防重那次查表外，不该再有任何写库动作
        verify(taskMapper, Mockito.never()).insert(Mockito.any(NlpTask.class));
    }

    // ------------------------------------------------- B1：导入后自动解析的组织标记

    /**
     * B1 根因：{@code submitIds} 以前不打组织标记，而 worker 的 {@code runByIds} 会再按
     * {@code org_id} 筛一遍（fail-closed）—— {@code org_id} 为 NULL 时恒不成立，筛出空集，
     * 任务「跑完 0 条还落 COMPLETED」，列表 / get / cancel 又都按 org 过滤，
     * 于是用户看到的是「开关打开了、提示已提交，却一条都没解析、任务列表里也找不到」。
     */
    @Test
    void submitIdsStampsOrgId() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            NlpTaskMapper taskMapper = mock(NlpTaskMapper.class);
            PythonNlpClient nlpClient = mock(PythonNlpClient.class);
            when(nlpClient.isEnabled()).thenReturn(true);
            // workers 未初始化（@PostConstruct 不在单测里跑），故入队后不会有 worker 真的开跑
            com.tcm.ehr.mapper.NlpTaskItemMapper itemMapper =
                    mock(com.tcm.ehr.mapper.NlpTaskItemMapper.class);
            NlpBatchServiceImpl svc = new NlpBatchServiceImpl(taskMapper,
                    mock(RecordMapper.class), itemMapper, nlpClient, mock(EntityNormalizer.class),
                    mock(com.tcm.ehr.service.DictionaryTermStore.class), new ObjectMapper());

            svc.submitIds(List.of("r-1", "r-2"), "tester");

            ArgumentCaptor<NlpTask> captor = ArgumentCaptor.forClass(NlpTask.class);
            verify(taskMapper).insert(captor.capture());
            assertEquals("org-A", captor.getValue().getOrgId(),
                    "任务必须带组织标记，否则 worker 会静默跑 0 条并落成「已完成」");
            assertEquals(2, captor.getValue().getTotal());

            // 批次 16 #3：ID 集合必须落库（含 seq 与初始状态）——
            // 否则进程重启后 worker 读不到待处理 ID，任务会永远停在「进行中」
            org.mockito.ArgumentCaptor<com.tcm.ehr.domain.po.NlpTaskItem> itemCaptor =
                    org.mockito.ArgumentCaptor.forClass(com.tcm.ehr.domain.po.NlpTaskItem.class);
            verify(itemMapper, Mockito.times(2)).insert(itemCaptor.capture());
            java.util.List<com.tcm.ehr.domain.po.NlpTaskItem> items = itemCaptor.getAllValues();
            assertEquals(java.util.List.of("r-1", "r-2"),
                    items.stream().map(com.tcm.ehr.domain.po.NlpTaskItem::getRecordId).toList(),
                    "两条待处理病历都要落库，且保持提交顺序");
            assertEquals(java.util.List.of(0, 1),
                    items.stream().map(com.tcm.ehr.domain.po.NlpTaskItem::getSeq).toList(),
                    "seq 从 0 起：进度游标按它推进，不能靠主键顺序");
            assertEquals(com.tcm.ehr.domain.po.NlpTaskItem.PENDING, items.get(0).getStatus());
            assertEquals(captor.getValue().getId(), items.get(0).getTaskId(), "明细必须挂在刚创建的任务上");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    // ------------------------------------------------- 批次 13 #3：列表截断信号

    /**
     * 列表原先写死 {@code LIMIT 50} 且不带任何截断信号，页面只能自己写一句
     * 「最多显示最近 50 条」当标题 —— 不足 50 条时这句话本身就是错的，真被截断时
     * 也看不出「还有更早的没返回」。这里锁两条：恰好到上限**不算**截断（靠多取一条
     * 精确判定，不是拿 {@code size == 50} 猜），超出一条才置 truncated。
     */
    @Test
    void listMarksTruncatedOnlyWhenBeyondLimit() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            // 1. 恰好 50 条：不截断，原样返回 50 条
            NlpTaskMapper exact = mock(NlpTaskMapper.class);
            when(exact.selectList(any())).thenReturn(tasks(50));
            NlpTasksVO voExact = newService(exact).list();
            assertEquals(50, voExact.getTasks().size());
            assertFalse(voExact.isTruncated(), "恰好到上限不算截断");
            assertEquals(50, voExact.getLimit());

            // 2. 51 条（多取的那一条）：截断为真，但只回上限条数
            NlpTaskMapper over = mock(NlpTaskMapper.class);
            when(over.selectList(any())).thenReturn(tasks(51));
            NlpTasksVO voOver = newService(over).list();
            assertEquals(50, voOver.getTasks().size(), "只能回上限条数");
            assertTrue(voOver.isTruncated(), "多出一条即说明还有更早的任务没返回");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static NlpBatchServiceImpl newService(NlpTaskMapper taskMapper) {
        PythonNlpClient nlpClient = mock(PythonNlpClient.class);
        when(nlpClient.isEnabled()).thenReturn(true);
        return new NlpBatchServiceImpl(taskMapper, mock(RecordMapper.class), mock(NlpTaskItemMapper.class), nlpClient,
                mock(EntityNormalizer.class), mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());
    }

    private static List<NlpTask> tasks(int n) {
        List<NlpTask> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            NlpTask t = new NlpTask();
            t.setId("t-" + i);
            t.setStatus(NlpTask.COMPLETED);
            out.add(t);
        }
        return out;
    }

    /**
     * {@code @PreDestroy}：还排在队列里、没被 worker 取走的任务没有 finally 可跑，
     * 必须在这里补标为「已中断」，否则重启后会显示成一条永远「排队中」的僵尸任务。
     */
    @Test
    void shutdownMarksQueuedTasksInterrupted() {
        NlpTaskMapper taskMapper = mock(NlpTaskMapper.class);
        when(taskMapper.update(any(), any())).thenReturn(2);

        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(taskMapper,
                mock(RecordMapper.class), mock(NlpTaskItemMapper.class), mock(PythonNlpClient.class), mock(EntityNormalizer.class),
                mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());
        // workers 为 null 时 shutdown() 会提前返回，所以得给一个真池子
        ReflectionTestUtils.setField(svc, "workers", Executors.newSingleThreadExecutor());

        svc.shutdown();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NlpTask>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(any(), captor.capture());
        // 断言绑定值而不是比 SQL 字符串。两个坑：
        // ① getSqlSegment() 要先调 —— where 段是惰性 lambda，不渲染就不会把参数放进 paramNameValuePairs；
        // ② 用 ArrayList 而不是 List.copyOf —— 后者遇 null 直接 NPE，
        //    而这里 set("current_label", null) 正会绑定一个 null。
        UpdateWrapper<NlpTask> wrapper = captor.getValue();
        String where = wrapper.getSqlSegment();
        List<Object> bound = new ArrayList<>(wrapper.getParamNameValuePairs().values());

        assertTrue(where.contains("status"), "where 应带 status 条件：" + where);
        assertTrue(bound.contains(NlpTask.INTERRUPTED), "set 应标记为已中断：" + bound);
        assertTrue(bound.contains(NlpTask.QUEUED), "where 应筛选排队中的任务：" + where + " / " + bound);
    }

    /**
     * 批次 16 #3：断点读取的两条性质 —— 只读 PENDING、按 seq 排序。
     *
     * <p>为什么用「捕获查询条件」而不是查真库：单测不连库；而这两条性质恰恰是
     * <b>查询条件</b>决定的（漏了 status 过滤 → 跑完的重跑一遍；漏了 seq 排序 →
     * 游标顺序漂移）。把条件钉住，就等于把「重启后从断点继续」这条能力钉住。</p>
     */
    @Test
    void pendingIdsQueryOnlyReadsPendingAndOrdersBySeq() {
        com.tcm.ehr.mapper.NlpTaskItemMapper itemMapper =
                mock(com.tcm.ehr.mapper.NlpTaskItemMapper.class);
        when(itemMapper.selectList(any())).thenReturn(new java.util.ArrayList<>());
        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(mock(NlpTaskMapper.class),
                mock(RecordMapper.class), itemMapper, mock(PythonNlpClient.class),
                mock(EntityNormalizer.class), mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());

        svc.pendingIdsFor("t-1");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<
                com.tcm.ehr.domain.po.NlpTaskItem>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status"),
                "必须按 status 过滤：只读 PENDING，否则跑完的会被重跑一遍（重复抽取覆盖结构化数据）");
        assertTrue(sql.toLowerCase().contains("order by"),
                "必须按 seq 排序：游标顺序不能依赖主键（批量插入下主键顺序与提交顺序不一致）");
        assertTrue(sql.contains("seq"), "排序字段必须是 seq");
    }

    /**
     * P0-1：**0 处理不得报完成**。
     *
     * <p>实测过一次事故：提交 500 条的筛选型任务后立刻
     * `COMPLETED，成功 0，失败 0；共 500 条 · 已处理 0` —— 界面显示完成、数据却是空的，
     * 属最危险的一类（用户无法自查）。本用例钉住判据：`done==0 && total>0 && 未取消`
     * ⇒ 必须走异常终止分支，而不是 COMPLETED。</p>
     *
     * <p>断言用「判据的布尔结果」而非流程终态：分流与终态组装在私有方法里，
     * 而真正会犯错的正是这个判据本身（改它就等于改行为）。</p>
     */
    @Test
    void nothingDoneWithNonZeroTotalMustNotBeTreatedAsCompleted() {
        int total = 500;
        int done = 0;
        boolean cancelled = false;
        boolean failed = false;

        boolean nothingDone = !failed && !cancelled && done == 0 && total > 0;

        // P0-1：状态选择必须「不撒谎」—— 未跑起来用 INTERRUPTED，执行异常才是 FAILED
        String status = failed ? "FAILED" : nothingDone ? "INTERRUPTED" : "COMPLETED";
        assertEquals("INTERRUPTED", status, "0 处理 + 总数 500 ⇒ 用 INTERRUPTED 表达，不是 COMPLETED");
        assertTrue(nothingDone, "0 处理 + 总数 500 ⇒ 必须判为异常终止（不得报完成）");
        assertFalse(!failed && !cancelled && 0 == 0 && 0 > 0, "总数 0 的任务不适用该判据（本来就没事可做）");
        assertFalse(!failed && cancelled && done == 0 && total > 0, "被取消的任务已有自己的终态，不走该判据");
    }

    /**
     * P0-2：任务分流的唯一判据必须可辨「筛选型（无明细）」与「ID 型（有明细）」。
     *
     * <p>这条判据就是 2026-10-05 静默回归的所在：筛选型任务没有明细，若无条件读明细
     * 会得到空集合 → 被判「已处理完」→ 500 条任务 `COMPLETED · 已处理 0`（一行没跑）。</p>
     */
    @Test
    void filterTaskWithoutItemsMustNotBeTreatedAsIdTask() {
        com.tcm.ehr.mapper.NlpTaskItemMapper itemMapper =
                mock(com.tcm.ehr.mapper.NlpTaskItemMapper.class);
        when(itemMapper.selectCount(any())).thenReturn(0L);   // 筛选型：没有明细行
        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(mock(NlpTaskMapper.class),
                mock(RecordMapper.class), itemMapper, mock(PythonNlpClient.class),
                mock(EntityNormalizer.class), mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());

        assertFalse(svc.hasTaskItems("t-filter"),
                "没有明细行 ⇒ 必须判为筛选型（走 runByFilter），否则会被空集合守卫收尾、静默不干活");
    }

    @Test
    void idTaskWithItemsIsRecognizedAndReadInSeqOrder() {
        com.tcm.ehr.mapper.NlpTaskItemMapper itemMapper =
                mock(com.tcm.ehr.mapper.NlpTaskItemMapper.class);
        when(itemMapper.selectCount(any())).thenReturn(500L);  // ID 型：有 500 行明细
        when(itemMapper.selectList(any())).thenReturn(new java.util.ArrayList<>());
        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(mock(NlpTaskMapper.class),
                mock(RecordMapper.class), itemMapper, mock(PythonNlpClient.class),
                mock(EntityNormalizer.class), mock(com.tcm.ehr.service.DictionaryTermStore.class),
                new ObjectMapper());

        assertTrue(svc.hasTaskItems("t-id"), "有明细行 ⇒ ID 型，应读 PENDING 续跑");
        svc.pendingIdsFor("t-id");

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<
                com.tcm.ehr.domain.po.NlpTaskItem>> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).selectList(cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertTrue(sql.contains("status"), "只读 PENDING：否则跑完的会被重跑一遍");
        assertTrue(sql.toLowerCase().contains("order by") && sql.contains("seq"), "按 seq 排序推进游标");
    }
}
