package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.QcTask;
import com.tcm.ehr.mapper.QcTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private QcBatchServiceImpl newService(QcTaskMapper taskMapper, RecordMapper recordMapper,
                                          OperationLogger operationLogger) {
        return new QcBatchServiceImpl(taskMapper, recordMapper, mock(QcServiceImpl.class),
                mock(QcRuleStore.class), operationLogger, new ObjectMapper());
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
     * 一次全库重算就会把审核员看不见的病历也重算并改写分数。</p>
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
        assertEquals("管理员", row.getRole(), "角色必须快照进 qc_task.role");
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

    // ------------------------------------------------------------------ 序列化契约

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
}
