package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 3：单条质控链路的事务边界。
 *
 * <p>{@code processOne} 先写 {@code records}（评分字段 + qc_results）再写
 * {@code review_tasks}，两段写必须同生共死。锁两件事：</p>
 * <ol>
 *   <li>复核任务写入失败（这里用唯一键冲突模拟）时异常必须外抛，交给事务回滚 records 的写入；
 *       吞掉的话会留下「分数已写、复核任务未写」的脏状态 —— 这条病历既不会被复核，
 *       也不会再进任何队列。</li>
 *   <li>正常路径下两段写都真实发生（只断言「抛异常」的话，空实现也能通过）。</li>
 * </ol>
 *
 * <p>不依赖评分分级来构造「待复核」入参：分级取决于要素缺失数与阈值，硬凑数据会很脆。
 * 这里直接给一条已存在的未作废任务，让代码走「更新既有任务」这条确定的分支。</p>
 */
class QcProcessOneTransactionTest {

    private QcServiceImpl service(RecordMapper recordMapper, ReviewTaskMapper reviewTaskMapper) {
        QcServiceImpl svc = new QcServiceImpl(reviewTaskMapper, new ObjectMapper(),
                mock(QcRuleStore.class));
        // QcServiceImpl 继承 ServiceImpl<Record>，写库走 baseMapper（批次 10 才统一成显式注入）
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);
        return svc;
    }

    /** 复核任务写失败 → 异常必须外抛，让事务回滚已写入的 records 评分 */
    @Test
    void reviewTaskWriteFailurePropagates() {
        RecordMapper recordMapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        ReviewTask existing = new ReviewTask();
        existing.setId("t-1");
        existing.setRecordId("r-1");
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(existing));
        doThrow(new DuplicateKeyException("uk_record_obsolete"))
                .when(reviewTaskMapper).updateById(any(ReviewTask.class));

        QcServiceImpl svc = service(recordMapper, reviewTaskMapper);

        Record r = new Record();
        r.setId("r-1");
        r.setStructuredData("{\"chiefComplaint\":\"头晕\"}");

        boolean threw = false;
        try {
            svc.processOne(r, new QcBatchResultVO(), new HashSet<>(), QcRuleSet.defaults());
        } catch (Exception e) {
            threw = true;
        }

        assertTrue(threw, "复核任务写入失败必须抛出，让事务回滚 records 的评分写入");
    }

    /** 正常路径：records 评分与复核任务两段写都发生，且复核任务挂在正确的病历上 */
    @Test
    void processOneWritesScoreAndReviewTask() {
        RecordMapper recordMapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);

        ReviewTask existing = new ReviewTask();
        existing.setId("t-2");
        existing.setRecordId("r-2");
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(existing));

        QcServiceImpl svc = service(recordMapper, reviewTaskMapper);

        Record r = new Record();
        r.setId("r-2");
        r.setStructuredData("{\"chiefComplaint\":\"头晕\"}");

        try {
            svc.processOne(r, new QcBatchResultVO(), new HashSet<>(), QcRuleSet.defaults());
        } catch (Exception ignored) {
            // 分级可能是「无效」（要素缺太多），那样不会碰复核任务；断言点在下面两处写库
        }

        // 真实写库口是 baseMapper.updateScoreFields（评分字段与 qc_results 一起落）
        verify(recordMapper).updateScoreFields(anyString(), any(Integer.class),
                anyString(), anyString(), anyString());

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(captor.capture());
        assertEquals("t-2", captor.getValue().getId());
    }
}
