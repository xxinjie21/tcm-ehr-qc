package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.NlpTask;
import com.tcm.ehr.mapper.NlpTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryFileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * {@code @PreDestroy}：还排在队列里、没被 worker 取走的任务没有 finally 可跑，
     * 必须在这里补标为「已中断」，否则重启后会显示成一条永远「排队中」的僵尸任务。
     */
    @Test
    void shutdownMarksQueuedTasksInterrupted() {
        NlpTaskMapper taskMapper = mock(NlpTaskMapper.class);
        when(taskMapper.update(any(), any())).thenReturn(2);

        NlpBatchServiceImpl svc = new NlpBatchServiceImpl(taskMapper,
                mock(RecordMapper.class), mock(PythonNlpClient.class), mock(EntityNormalizer.class),
                mock(IDictionaryFileService.class), new ObjectMapper());
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
}
