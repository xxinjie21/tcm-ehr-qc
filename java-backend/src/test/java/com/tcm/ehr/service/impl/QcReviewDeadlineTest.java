package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 复核时限的口径（M11）。
 *
 * 时限过去写死在 Mapper 的 SQL 里（{@code DATE_ADD(now, INTERVAL 7 DAY)}）—— 那是**自然日**，
 * 而 DDL 注释、seed 脚本与设计文档都写「7 个工作日」，{@code addWorkdays} 一直是死代码。
 * 现在改为 Java 侧算好再传，本用例把「传进 mapper 的必须是跳周末的 7 个工作日」钉住。
 */
class QcReviewDeadlineTest {

    @Test
    void deadlineIsSevenWorkdaysNotCalendarDays() {
        RecordMapper recordMapper = mock(RecordMapper.class);
        ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
        QcServiceImpl svc = new QcServiceImpl(reviewTaskMapper, new ObjectMapper(),
                mock(QcRuleStore.class));
        // QcServiceImpl 继承 ServiceImpl<Record>，写库走 baseMapper
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);

        // 阈值调成「必落待复核」：invalid=0（分数不可能低于它）、
        // seriousFullMissing=99（真缺失规则不触发）、qualified=101（分数不可能达标）
        QcRuleSet rules = QcRuleSet.defaults();
        rules.getThresholds().setInvalid(0);
        rules.getThresholds().setSeriousFullMissing(99);
        rules.getThresholds().setQualified(101);

        Record r = new Record();
        r.setId("r-dl");
        r.setOrgId("org-A");
        r.setStructuredData("{\"chiefComplaint\":\"头晕\"}");

        try {
            svc.processOne(r, new QcBatchResultVO(), new HashSet<>(), rules);
        } catch (Exception ignored) {
            // 分级之外的异常可接受；断言点在下面对 mapper 入参的捕获上
        }

        ArgumentCaptor<LocalDateTime> nowCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> dlCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reviewTaskMapper).upsertPending(eq("r-dl"), eq("org-A"), any(), anyString(),
                nowCap.capture(), dlCap.capture());
        verify(reviewTaskMapper, never()).obsoleteActive(anyString(), any());

        LocalDateTime now = nowCap.getValue();
        LocalDateTime deadline = dlCap.getValue();

        // 1) 与独立实现的「顺延 7 个工作日」互证（避免只断言「非空」这种空实现也能过的写法）
        LocalDateTime expect = now;
        int left = 7;
        while (left > 0) {
            expect = expect.plusDays(1);
            DayOfWeek d = expect.getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        assertEquals(expect, deadline, "复核时限应为顺延 7 个工作日（跳周末）");

        // 2) 定义性属性：落点必须是工作日 —— 这正是 INTERVAL 7 DAY 会违反的那条
        assertNotEquals(DayOfWeek.SATURDAY, deadline.getDayOfWeek(), "时限不该落在周六");
        assertNotEquals(DayOfWeek.SUNDAY, deadline.getDayOfWeek(), "时限不该落在周日");

        // 3) 7 个工作日不可能早于 7 个自然日（自然日口径反而是更早的那个）
        assertFalse(deadline.isBefore(now.plusDays(7)), "工作日口径不会比自然日更早");
    }
}
