package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.QcTask;
import com.tcm.ehr.domain.vo.QcTaskVO;
import com.tcm.ehr.domain.vo.QcTasksVO;
import com.tcm.ehr.mapper.QcTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量重算任务（§七 L5）的两条关键契约。
 *
 * <p><b>① 角色快照</b>：worker 跑在后台线程，{@code RequestUtils.currentRole()} 读的是线程绑定的
 * {@code RequestContextHolder}，取到的是字符串 {@code "unknown"}（不是 null）。拿它去
 * {@code RecordFilter.build} 会让 {@code domainGrade} 返回 null，数据域过滤退化成
 * 「不过滤 = 全库」—— 表面看任务跑通了，实际重算了用户看不见的病历。
 * 这条测试锁住「提交线程把角色写进 {@code qc_task.role}、worker 用它重建过滤器、
 * 审计日志也用它回填操作人」。</p>
 *
 * <p><b>② 收尾状态</b>：停机时没跑完不能声称「已完成」。</p>
 *
 * <p>放在 {@code service.impl} 包内是为了直接测包级可见的 {@link QcBatchServiceImpl#endStatus}。</p>
 */
class QcBatchServiceImplTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 提交互斥统一走 DistLock → Redisson RLock（批次 16.1）。默认打桩成「拿到锁」 */
    private RLock submitLock;

    private QcBatchServiceImpl newService(QcTaskMapper taskMapper, RecordMapper recordMapper,
                                          OperationLogger operationLogger) {
        RedissonClient redisson = mock(RedissonClient.class);
        submitLock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(submitLock);
        when(submitLock.isHeldByCurrentThread()).thenReturn(true);
        stubTryLock(true);
        return new QcBatchServiceImpl(taskMapper, recordMapper, mock(QcServiceImpl.class),
                mock(QcRuleStore.class), operationLogger, new ObjectMapper(), new DistLock(redisson));
    }

    /** {@code tryLock(3s)} 的桩：true=拿到锁，false=等待超时（多实例里别人正持锁） */
    private void stubTryLock(boolean locked) {
        try {
            when(submitLock.tryLock(anyLong(), any())).thenReturn(locked);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 收尾状态

    @Test
    void endStatus_normalCompletionIsCompleted() {
        assertEquals(QcTask.COMPLETED, QcBatchServiceImpl.endStatus(true, false, 500, 500));
        // 范围内没有病历：done == total == 0，同样是正常结束
        assertEquals(QcTask.COMPLETED, QcBatchServiceImpl.endStatus(true, false, 0, 0));
    }

    @Test
    void endStatus_cancelledWinsOverCompleted() {
        assertEquals(QcTask.CANCELLED, QcBatchServiceImpl.endStatus(true, true, 3, 500));
    }

    /** 停机时没跑完，不能声称「已完成」 */
    @Test
    void endStatus_shutdownWhileUnfinishedIsInterrupted() {
        assertEquals(QcTask.INTERRUPTED, QcBatchServiceImpl.endStatus(false, false, 50, 500));
    }

    /** 反向：停机窗口内恰好跑完的仍是「已完成」 */
    @Test
    void endStatus_shutdownAfterFinishingStaysCompleted() {
        assertEquals(QcTask.COMPLETED, QcBatchServiceImpl.endStatus(false, false, 500, 500));
    }

    @Test
    void endStatus_shutdownWithCancelledStaysCancelled() {
        assertEquals(QcTask.CANCELLED, QcBatchServiceImpl.endStatus(false, true, 10, 500));
    }

    /**
     * {@code @PreDestroy}：还排在队列里、没被 worker 取走的任务没有 finally 可跑，
     * 必须在这里补标「已中断」，否则重启后会显示成一条永远「排队中」的僵尸任务。
     */
    @Test
    void shutdownMarksQueuedTasksInterrupted() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        when(taskMapper.update(any(), any())).thenReturn(2);

        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));
        // workers 非 null，shutdown() 才会往下走，所以得给一个真池子
        ReflectionTestUtils.setField(svc, "workers", Executors.newSingleThreadExecutor());

        svc.shutdown();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<QcTask>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(any(), captor.capture());
        // 断言绑定值而不是比 SQL 字符串（同 NlpBatchServiceImplTest）：先调 getSqlSegment()
        // 把 where 段渲染出来，再取绑定值
        UpdateWrapper<QcTask> wrapper = captor.getValue();
        String where = wrapper.getSqlSegment();
        List<Object> bound = new ArrayList<>(wrapper.getParamNameValuePairs().values());

        assertTrue(where.contains("status"), "where 应带 status 条件：" + where);
        assertTrue(bound.contains(QcTask.INTERRUPTED), "set 应标记为已中断：" + bound);
        assertTrue(bound.contains(QcTask.QUEUED), "where 应筛选排队中的任务：" + where + " / " + bound);
    }

    // ------------------------------------------------------------------ 角色快照（最关键）

    /**
     * 提交时把「操作人 + 角色」冻结进 {@code qc_task}。
     *
     * <p>这是整条异步链路的地基：worker 若用 {@code "unknown"} 重建过滤器，
     * 一次全库重算就会把别的组织看不见的病历也重算并改写分数。</p>
     */
    @Test
    void submitFreezesOperatorAndRoleOntoTheTaskRow() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        // 造一个 insert 后能回读的行
        when(taskMapper.insert(any(QcTask.class))).thenAnswer(inv -> {
            QcTask t = inv.getArgument(0);
            t.setId("task-1");
            return 1;
        });
        RecordMapper recordMapper = mock(RecordMapper.class);
        when(recordMapper.selectCount(any())).thenReturn(5L);
        OperationLogger logger = mock(OperationLogger.class);

        QcBatchServiceImpl svc = newService(taskMapper, recordMapper, logger);
        // @Value 在直接 new 时不生效，手工注入上限（否则 maxRecords=0，5 条就会被判超限）
        ReflectionTestUtils.setField(svc, "maxRecords", 40000);
        // 模拟「提交线程」：RequestContextHolder 里挂着角色
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute("currentUsername", "admin");
        req.setAttribute("currentRole", "管理员");
        req.setAttribute("currentOrgId", "grp-default-2026");
        req.setAttribute("currentOrgRole", "member");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
        try {
            svc.submit(null);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QcTask> captor = ArgumentCaptor.forClass(QcTask.class);
        verify(taskMapper).insert(captor.capture());
        QcTask row = captor.getValue();
        assertEquals("管理员", row.getRole(), "角色必须快照进 qc_task.role（回填审计日志）");
        assertEquals("grp-default-2026", row.getOrgId(),
                "组必须快照进 qc_task.org_id（worker 重建 RecordFilter）");
        assertEquals("admin", row.getCreatedBy(), "操作人必须快照进 qc_task.created_by");
        assertEquals(5, row.getTotal());
    }

    /** 已有任务在跑时拒绝重复提交（防重查表，不用 Redis 全局锁） */
    @Test
    void submitRejectedWhenTaskAlreadyActive() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        when(taskMapper.selectCount(any())).thenReturn(1L);

        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));

        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> svc.submit(null));
        assertTrue(e.getMessage().contains("已有重算任务"), e.getMessage());
        // 拒绝路径不该写库
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).insert(any(QcTask.class));
    }

    @Test
    @DisplayName("拿不到提交锁：直接拒绝，且不做无锁提交（不能落库）")
    void submitRejectedWhenSubmitLockNotAcquired() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));
        // 必须在 newService 之后覆盖 —— 它默认把锁打桩成「拿到了」
        // 桩改成「等 3s 没拿到锁」—— 这正是多实例并发提交时的情形
        stubTryLock(false);

        ConcurrentOperationException ex = assertThrows(ConcurrentOperationException.class,
                () -> svc.submit(null));
        assertTrue(ex.getMessage().contains("稍后重试"), ex.getMessage());
        // 关键：没拿到锁就绝不能继续插任务，否则又变回 check-then-act
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).insert(any(QcTask.class));
        // 也没拿到锁，自然不该去释放
        org.mockito.Mockito.verify(submitLock, org.mockito.Mockito.never()).unlock();
    }

    @Test
    @DisplayName("锁内查重被拒：也必须释放锁，否则后续提交全被卡死")
    void submitReleasesLockWhenDuplicateRejected() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));
        // 锁内查到已有活跃任务 → 抛异常
        when(taskMapper.selectCount(any())).thenReturn(1L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.submit(null));
        assertTrue(ex.getMessage().contains("排队或运行中"));
        // finally 必须释放，否则这一次异常会把之后所有提交都堵死
        verify(submitLock).unlock();
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).insert(any(QcTask.class));
    }

    // ------------------------------------------------------------------ 序列化契约

    // ------------------------------------------------------------------ 取消 / 认领互斥

    /**
     * 排队中取消必须带 {@code status='QUEUED'} 条件。
     *
     * <p>事故形状：worker 已 poll 到任务 id、还没落库，用户此刻点取消 ——
     * 取消若用无条件的 {@code updateById}，随后 worker 拿取任务时的快照
     * 无条件写回 {@code RUNNING}，取消被静默抹掉，任务跑完还会覆写成
     * {@code COMPLETED}。多实例下同理：两条 UPDATE 竞争同一行，靠条件让库来仲裁。</p>
     */
    @Test
    @DisplayName("排队中取消：条件命中才落 CANCELLED（不再无条件 updateById）")
    void cancelQueuedTaskUsesStatusGuardedUpdate() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        QcTask queued = new QcTask();
        queued.setId("task-c1");
        queued.setStatus(QcTask.QUEUED);
        queued.setOrgId("grp-default-2026");
        when(taskMapper.selectById("task-c1")).thenReturn(queued);
        when(taskMapper.update(any(), any())).thenReturn(1);

        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));
        bindRequestContext();
        try {
            QcTaskVO vo = svc.cancel("task-c1");
            assertEquals(QcTask.CANCELLED, vo.getStatus());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<QcTask>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper).update(any(), captor.capture());
        // 先渲染 where 段再取绑定值：UpdateWrapper 的 eq 值是惰性物化的
        // （同 shutdownMarksQueuedTasksInterrupted 的处理）
        String where = captor.getValue().getSqlSegment();
        List<Object> bound = new ArrayList<>(captor.getValue().getParamNameValuePairs().values());
        assertTrue(where.contains("status"), "where 必须带 status 条件：" + where);
        assertTrue(bound.contains(QcTask.QUEUED), "条件值应为 QUEUED：" + bound);
        assertTrue(bound.contains(QcTask.CANCELLED), "set 值应为 CANCELLED：" + bound);
        // 无条件按主键整行覆写这条路不能再走
        org.mockito.Mockito.verify(taskMapper, org.mockito.Mockito.never()).updateById(any(QcTask.class));
    }

    /**
     * 反向：取消的条件更新命中 0 行 = 已被 worker 认领。
     * 此时要重读一次、改走「只置取消位」分支，而不是把任务按 CANCELLED 返回给用户。
     */
    @Test
    @DisplayName("取消时任务刚被认领：改置取消位，不谎称已取消")
    void cancelFallsBackToCancelFlagWhenAlreadyClaimed() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        QcTask stillQueued = new QcTask();
        stillQueued.setId("task-c2");
        stillQueued.setStatus(QcTask.QUEUED);
        stillQueued.setOrgId("grp-default-2026");
        QcTask alreadyRunning = new QcTask();
        alreadyRunning.setId("task-c2");
        alreadyRunning.setStatus(QcTask.RUNNING);
        alreadyRunning.setOrgId("grp-default-2026");
        // 第一次读是取消入口的快照，第二次读是条件更新失败后的重读
        when(taskMapper.selectById("task-c2")).thenReturn(stillQueued, alreadyRunning);
        when(taskMapper.update(any(), any())).thenReturn(0);

        QcBatchServiceImpl svc = newService(taskMapper, mock(RecordMapper.class), mock(OperationLogger.class));
        bindRequestContext();
        try {
            QcTaskVO vo = svc.cancel("task-c2");
            assertEquals(QcTask.RUNNING, vo.getStatus(),
                    "已被认领时不能对用户显示『已取消』；由 worker 在条/页边界自行收尾");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<QcTask>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).update(any(), captor.capture());
        String secondWhere = captor.getAllValues().get(1).getSqlSegment();
        List<Object> secondBound =
                new ArrayList<>(captor.getAllValues().get(1).getParamNameValuePairs().values());
        assertTrue(secondBound.contains(1), "第二次更新应置 cancel_requested=1：" + secondBound);
        assertTrue(!secondWhere.contains(QcTask.CANCELLED),
                "已被认领时不能再把行改成 CANCELLED：" + secondWhere);
    }

    /**
     * worker 认领同样要带 {@code status='QUEUED'} 条件：认领失败（被取消 / 已进终态）
     * 就不能再跑。这条同时保证「不再用取任务时的快照无条件写回 RUNNING」。
     */
    @Test
    @DisplayName("认领竞态落败：任务不再执行，也不碰病历查询")
    void workerSkipsTaskWhenClaimLosesTheRace() {
        QcTaskMapper taskMapper = mock(QcTaskMapper.class);
        RecordMapper recordMapper = mock(RecordMapper.class);
        QcTask queued = new QcTask();
        queued.setId("task-c3");
        queued.setStatus(QcTask.QUEUED);
        queued.setOrgId("grp-default-2026");
        queued.setTotal(5);
        when(taskMapper.selectById("task-c3")).thenReturn(queued);
        // 条件更新命中 0 行：任务在「取出来」与「认领」之间被取消了
        when(taskMapper.update(any(), any())).thenReturn(0);

        QcBatchServiceImpl svc = newService(taskMapper, recordMapper, mock(OperationLogger.class));
        ReflectionTestUtils.invokeMethod(svc, "runTask", "task-c3");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<QcTask>> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(taskMapper, org.mockito.Mockito.times(1)).update(any(), captor.capture());
        String where = captor.getValue().getSqlSegment();
        List<Object> bound = new ArrayList<>(captor.getValue().getParamNameValuePairs().values());
        assertTrue(where.contains("status"), "认领必须带 status 条件：" + where);
        assertTrue(bound.contains(QcTask.QUEUED) && bound.contains(QcTask.RUNNING),
                "where=QUEUED、set=RUNNING：" + bound);
        // 认领失败就一条病历都不该查、也不该去读规则快照
        org.mockito.Mockito.verify(recordMapper, org.mockito.Mockito.never()).selectList(any());
    }

    /** 模拟「请求线程」的纯值上下文（只放 cancel 判权要用的组织） */
    private void bindRequestContext() {
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute("currentUsername", "admin");
        req.setAttribute("currentRole", "管理员");
        req.setAttribute("currentOrgId", "grp-default-2026");
        req.setAttribute("currentOrgRole", "owner");
        RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
    }

    /** null 不是失败：序列化成 {@code "null"}、读回为 null，语义是「不限范围」 */
    @Test
    void nullFiltersSerializeToJsonNull() {
        QcBatchServiceImpl svc = newService(mock(QcTaskMapper.class), mock(RecordMapper.class),
                mock(OperationLogger.class));
        assertEquals("null", svc.writeJsonStrict(null));
    }

    @Test
    void normalFiltersSerialize() {
        QcBatchServiceImpl svc = newService(mock(QcTaskMapper.class), mock(RecordMapper.class),
                mock(OperationLogger.class));
        FiltersDTO f = new FiltersDTO();
        f.setDepartment("中医内科");
        f.setDateRange(List.of("2026-01-01", "2026-01-31"));

        String json = svc.writeJsonStrict(f);

        assertTrue(json.contains("中医内科"), json);
        assertTrue(json.contains("2026-01-01"), json);
    }

    // ------------------------------------------------- 列表截断信号（与 NLP 列表同构）

    /**
     * 列表原先写死 {@code LIMIT 50} 且回包是裸数组，页面只能自己写一句「最多显示最近 50 条」
     * 当标题 —— 不足 50 条时这句话本身就是错的，真被截断时也看不出「还有更早的没返回」。
     * 这里锁两条：恰好到上限**不算**截断（靠多取一条精确判定，不是拿 {@code size == 50} 猜），
     * 超出一条才置 truncated。
     */
    @Test
    void listMarksTruncatedOnlyWhenBeyondLimit() {
        bindRequestContext();
        try {
            // 1. 恰好 50 条：不截断，原样返回 50 条
            QcTaskMapper exact = mock(QcTaskMapper.class);
            when(exact.selectList(any())).thenReturn(tasks(50));
            QcTasksVO voExact = newService(exact, mock(RecordMapper.class), mock(OperationLogger.class)).list();
            assertEquals(50, voExact.getTasks().size());
            assertFalse(voExact.isTruncated(), "恰好到上限不算截断");
            assertEquals(50, voExact.getLimit());

            // 2. 51 条（多取的那一条）：截断为真，但只回上限条数
            QcTaskMapper over = mock(QcTaskMapper.class);
            when(over.selectList(any())).thenReturn(tasks(51));
            QcTasksVO voOver = newService(over, mock(RecordMapper.class), mock(OperationLogger.class)).list();
            assertEquals(50, voOver.getTasks().size(), "只能回上限条数");
            assertTrue(voOver.isTruncated(), "多出一条即说明还有更早的任务没返回");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static List<QcTask> tasks(int n) {
        List<QcTask> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            QcTask t = new QcTask();
            t.setId("t-" + i);
            t.setStatus(QcTask.COMPLETED);
            out.add(t);
        }
        return out;
    }
}
