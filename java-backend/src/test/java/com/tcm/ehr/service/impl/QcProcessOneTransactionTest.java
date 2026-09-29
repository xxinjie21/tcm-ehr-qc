package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 批次 3/4：单条质控链路的事务边界。
 *
 * <p>{@code processOne} 先写 {@code records}（评分字段 + qc_results）再写
 * {@code review_tasks}，两段写必须同生共死。锁两件事：</p>
 * <ol>
 *   <li>复核任务写入失败时异常必须外抛，交给事务回滚 records 的写入；
 *       吞掉会留下「分数已写、复核任务未写」的脏状态 —— 这条病历既不会被复核，
 *       也不会再进任何队列。</li>
 *   <li>正常路径下两段写都真实发生（只断言「抛异常」的话，空实现也能通过）。</li>
 * </ol>
 *
 * <p><b>为什么不断言「待复核」分支</b>：分级取决于要素缺失数与阈值
 * （{@code fullMissing >= 3} 直接判「无效」），硬凑数据会随阈值调整而失效。
 * 这里用只有主诉的病历，稳定落在「无效 → 作废活跃任务」这条路径；
 * 两个分支在 {@code upsertReviewTask} 里是同一个 if/else，异常语义完全一致。</p>
 */
class QcProcessOneTransactionTest {

    private QcServiceImpl service(RecordMapper recordMapper, ReviewTaskMapper reviewTaskMapper) {
        QcServiceImpl svc = new QcServiceImpl(reviewTaskMapper, new ObjectMapper(),
                mock(QcRuleStore.class));
        // QcServiceImpl 继承 ServiceImpl<Record>，写库走 baseMapper（批次 10 才统一成显式注入）
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);
        return svc;
    }

    /** 只有主诉的病历：要素严重缺失，稳定判「无效」 */
    private Record sparseRecord(String id) {
        Record r = new Record();
        r.setId(id);
        r.setStructuredData("{\"chiefComplaint\":\"头晕\"}");
        return r;
    }

    /** 复核任务写失败 → 异常必须外抛，让事务回滚已写入的 records 评分 */
    @Test
    void reviewTaskWriteFailurePropagates() {
        RecordMapper recordMapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        doThrow(new DuplicateKeyException("uk_record_active"))
                .when(reviewTaskMapper).obsoleteActive(anyString(), any());

        QcServiceImpl svc = service(recordMapper, reviewTaskMapper);

        boolean threw = false;
        try {
            svc.processOne(sparseRecord("r-1"), new QcBatchResultVO(),
                    new HashSet<>(), QcRuleSet.defaults());
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

        QcServiceImpl svc = service(recordMapper, reviewTaskMapper);

        try {
            svc.processOne(sparseRecord("r-2"), new QcBatchResultVO(),
                    new HashSet<>(), QcRuleSet.defaults());
        } catch (Exception ignored) {
            // 分级相关异常可接受；断言点在下面两处「确实写库了」
        }

        // 真实写库口是 baseMapper.updateScoreFields（评分字段与 qc_results 一起落）
        verify(recordMapper).updateScoreFields(anyString(), any(Integer.class),
                anyString(), anyString(), anyString());
        verify(reviewTaskMapper).obsoleteActive(eq("r-2"), any());
    }
}
