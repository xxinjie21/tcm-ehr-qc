package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.ReviewDTO;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务注解的落点（批3·B3-1）。
 *
 * <p>锁的是一件很容易写错的事：{@code @Transactional} 只在<b>经过代理的 public 方法</b>上生效。
 * 本仓库真正干活的是 private 方法（{@code RecordServiceImpl.doDelete}、
 * {@code QcServiceImpl.processOne}），它们只被同类自调用 —— 注解加在那里等于没加，
 * 而代码看起来「已经加过事务了」。所以这里断言注解落在 public 入口上。</p>
 *
 * <p>另外两处<b>刻意不加</b>事务，不在这里断言、只在实现里写了原因：
 * {@code DictionaryServiceImpl.importDictionary}（方法内没有 DB 写，只有文件与 ES，
 * 都不随事务回滚）与 {@code QcServiceImpl.processOne}（private + 调用处吞异常，
 * 逐条独立提交是那里的既有设计）。</p>
 */
class TransactionAnnotationTest {

    private static void assertTransactional(Class<?> type, String method, Class<?>... params)
            throws NoSuchMethodException {
        Method m = type.getMethod(method, params);
        assertTrue(m.isAnnotationPresent(Transactional.class),
                type.getSimpleName() + "#" + method + " 应有 @Transactional：多写操作必须原子，"
                        + "否则中途失败会留下子表已删、主表未删这类的孤儿状态");
    }

    /** 删除会先清 review_tasks 再删 records（fk_review_record），两段写必须同生共死 */
    @Test
    void recordDeletionIsTransactional() throws NoSuchMethodException {
        assertTransactional(RecordServiceImpl.class, "deleteRecords", DeleteRecordsDTO.class);
        assertTransactional(RecordServiceImpl.class, "deleteByFilter", FiltersDTO.class);
    }

    /** 复核会写结构化数据 + 评分 + 任务状态，中途失败会让分数与任务状态对不上 */
    @Test
    void reviewIsTransactional() throws NoSuchMethodException {
        assertTransactional(ReviewServiceImpl.class, "review", String.class, ReviewDTO.class);
    }
}
