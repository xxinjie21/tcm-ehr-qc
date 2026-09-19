package com.tcm.ehr.service;

import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTaskVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.impl.ReviewServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 复核服务层测试（批D·5.1，纯 Mockito + 真实 QcScorer/ObjectMapper，不加载 Spring 容器）：
 * 保持待复核 / 复核通过流转、无待办任务返回 null、超时计算属性。
 */
class ReviewServiceTest {

    private RecordMapper recordMapper;
    private ReviewTaskMapper reviewTaskMapper;
    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        recordMapper = Mockito.mock(RecordMapper.class);
        reviewTaskMapper = Mockito.mock(ReviewTaskMapper.class);
        service = new ReviewServiceImpl(recordMapper, new ObjectMapper());
        // ServiceImpl 的 baseMapper 由 Spring 注入，测试中手动设置
        ReflectionTestUtils.setField(service, "baseMapper", reviewTaskMapper);
    }

    /** 完整结构化数据（6 核心字段齐全 → 100 合格）；withFormula=false 时缺方剂 → 85 待复核 */
    private String structured(boolean withFormula) {
        String formula = withFormula ? "[{\"content\":\"济生肾气丸\",\"sourceText\":\"济生肾气丸\"}]" : "[]";
        return "{\"diseases\":[{\"content\":\"水肿\"}],\"symptoms\":[{\"content\":\"双下肢水肿\"}],"
                + "\"tongueList\":[{\"content\":\"舌淡\"}],\"pulseList\":[{\"content\":\"脉沉细\"}],"
                + "\"patternList\":[{\"content\":\"脾肾阳虚\"}],\"causeList\":[],"
                + "\"treatmentList\":[{\"content\":\"温补脾肾\"}],"
                + "\"formulaList\":" + formula + ",\"herbs\":[{\"name\":\"附子\",\"dosage\":\"10g\"}]}";
    }

    private Record record(String id, boolean withFormula) {
        Record r = new Record();
        r.setId(id);
        r.setGender("男");
        r.setAge("50岁");
        r.setPulse("脉沉细");
        r.setTongue("舌淡");
        r.setPattern("脾肾阳虚");
        r.setPrescription("附子10g");
        r.setStructuredData(structured(withFormula));
        return r;
    }

    private ReviewTask task(String id, String recordId) {
        ReviewTask t = new ReviewTask();
        t.setId(id);
        t.setRecordId(recordId);
        t.setStatus("pending");
        t.setIssueType("缺失字段");
        t.setScore(85);
        t.setIsObsolete(0);
        t.setCreateTime(LocalDateTime.now().minusDays(1));
        return t;
    }

    @Test
    void keepPendingWhenStillNotQualified() {
        Record r = record("rec-1", false);
        ReviewTask t = task("task-1", "rec-1");
        when(recordMapper.selectById("rec-1")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(t));

        ReviewResultVO vo = service.review("rec-1", null);

        assertNotNull(vo);
        assertEquals("待复核", vo.getStatus());
        assertEquals(85, vo.getScore());
        assertEquals("pending", t.getStatus());
        assertTrue(vo.getErrors().stream().anyMatch(e -> "核心字段缺失".equals(e.getType())));
    }

    @Test
    void completeWhenCorrectedBecomesQualified() {
        Record r = record("rec-2", false);
        ReviewTask t = task("task-2", "rec-2");
        when(recordMapper.selectById("rec-2")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(t));

        // 修正：补上方剂 → 100 合格 → 任务完成
        Map<String, Object> corrected = new ObjectMapper().readValue(structured(true), Map.class);
        ReviewDTO dto = new ReviewDTO();
        dto.setCorrectedData(corrected);

        ReviewResultVO vo = service.review("rec-2", dto);

        assertNotNull(vo);
        assertEquals("已完成", vo.getStatus());
        assertEquals(100, vo.getScore());
        assertEquals("completed", t.getStatus());
        assertNotNull(t.getReviewedBy());
        assertNotNull(t.getCompletedTime());
    }

    @Test
    void returnNullWhenNoActiveTask() {
        when(recordMapper.selectById("rec-3")).thenReturn(record("rec-3", true));
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of());

        assertNull(service.review("rec-3", null));
    }

    @Test
    void listTasksMarksOverdue() {
        ReviewTask t = task("task-4", "rec-4");
        t.setDeadlineTime(LocalDateTime.now().minusDays(1));
        when(reviewTaskMapper.selectPage(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    com.baomidou.mybatisplus.extension.plugins.pagination.Page<ReviewTask> page = inv.getArgument(0);
                    page.setRecords(List.of(t));
                    page.setTotal(1);
                    return page;
                });
        when(recordMapper.selectById("rec-4")).thenReturn(record("rec-4", false));

        ReviewTasksVO vo = service.listTasks(1, 10, "待复核");

        assertEquals(1, vo.getTotal());
        ReviewTaskVO first = vo.getTasks().get(0);
        assertEquals("待复核", first.getStatus());
        assertTrue(first.isOverdue());
    }

    @Test
    void recordUtilHashStableAndDistinct() {
        // 顺带锁定"去重口径与 textHash 一致"（批F·7.1）
        Record a = record("rec-a", false);
        Record b = record("rec-b", false);
        b.setPrescription("附子20g");
        assertEquals(RecordUtil.textHash(a), RecordUtil.textHash(record("rec-a2", false)));
        assertTrue(!RecordUtil.textHash(a).equals(RecordUtil.textHash(b)));
    }
}
