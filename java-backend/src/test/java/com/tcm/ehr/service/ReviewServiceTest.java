package com.tcm.ehr.service;

import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTaskVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.mapper.QcRuleMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IReviewWriteService;
import com.tcm.ehr.service.impl.ReviewServiceImpl;
import com.tcm.ehr.service.impl.ReviewWriteServiceImpl;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** 组绑定的请求上下文：阶段 2 起所有数据读取都需要它 */
    private static final String GROUP = "grp-review";

    private void loginAsGroup() {
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentUsername", "reviewer");
        req.setAttribute("currentRole", "用户");
        req.setAttribute("currentOrgId", GROUP);
        req.setAttribute("currentOrgRole", "member");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(req));
    }

    @BeforeEach
    void setUp() {
        loginAsGroup();
        recordMapper = Mockito.mock(RecordMapper.class);
        reviewTaskMapper = Mockito.mock(ReviewTaskMapper.class);
        // QcRuleStore 的 init() 是 @PostConstruct，测试里不调用 → get() 返回 null
        // → QcScorer 落回内置默认规则（正是要测的口径）
        // 复核提交修正前会跑一遍归一（否则人工新输入的词没有 normLevel，
        // 会被 QcScorer 当成未标准化而扣分）。mock 的 EsTermNormalizer 必须
        // 像真实实现一样返回非 null 的结果 —— 契约上它就不返回 null，
        // 之前直接 mock 返回 null 导致 normEntities NPE。
        EsTermNormalizer termNormalizer = org.mockito.Mockito.mock(EsTermNormalizer.class);
        Mockito.when(termNormalizer.normalize(Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyString()))
                .thenAnswer(inv -> new EsTermNormalizer.NormalizeResult(
                        inv.getArgument(2), 1));
        QcRuleStore ruleStore = new QcRuleStore(new ObjectMapper(),
                org.mockito.Mockito.mock(QcRuleMapper.class));
        // 复核写库段自批次 26.4 起是独立 Bean（事务落点），入口只做归一后委托
        IReviewWriteService writeService = new ReviewWriteServiceImpl(recordMapper, reviewTaskMapper,
                new ObjectMapper(), ruleStore);
        service = new ReviewServiceImpl(recordMapper,
                new EntityNormalizer(termNormalizer, new ObjectMapper()), writeService);
        // ServiceImpl 的 baseMapper 由 Spring 注入，测试中手动设置
        ReflectionTestUtils.setField(service, "baseMapper", reviewTaskMapper);
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 完整结构化数据（6 核心要素齐全）。
     *
     * <p>两处与批M 之后的口径对齐，别按旧注释理解：<b>方剂已不是完整性要素</b>，
     * 所以缺方剂不影响完整性扣分；实体都不带 {@code normLevel}，术语标准化按「未命中词典的
     * 实体数」计数（每个 -1、上限 -5），故满分被扣 4 或 5 分。</p>
     */
    private String structured(boolean withFormula) {
        return structured(withFormula, "附子");
    }

    private String structured(boolean withFormula, String herbName) {
        String formula = withFormula ? "[{\"content\":\"济生肾气丸\",\"sourceText\":\"济生肾气丸\"}]" : "[]";
        return "{\"diseases\":[{\"content\":\"水肿\"}],\"symptoms\":[{\"content\":\"双下肢水肿\"}],"
                + "\"tongueList\":[{\"content\":\"舌淡\"}],\"pulseList\":[{\"content\":\"脉沉细\"}],"
                + "\"patternList\":[{\"content\":\"脾肾阳虚\"}],\"causeList\":[],"
                + "\"treatmentList\":[{\"content\":\"温补脾肾\"}],"
                + "\"formulaList\":" + formula + ",\"herbs\":[{\"name\":\"" + herbName + "\",\"dosage\":\"10g\"}]}";
    }

    private Record record(String id, boolean withFormula) {
        return record(id, withFormula, "附子");
    }

    /**
     * 中药名可控。默认一致性规则里「脾肾阳虚-中药」期望附子/肉桂 ——
     * 换成「桂枝」即可造出「证候-中药」逻辑冲突 → 待复核。
     */
    private Record record(String id, boolean withFormula, String herbName) {
        Record r = new Record();
        r.setId(id);
        r.setGender("男");
        r.setAge("50岁");
        r.setPulse("脉沉细");
        r.setTongue("舌淡");
        r.setPattern("脾肾阳虚");
        r.setPrescription(herbName + "10g");
        r.setOrgId(GROUP);
        r.setStructuredData(structured(withFormula, herbName));
        return r;
    }

    private ReviewTask task(String id, String recordId) {
        ReviewTask t = new ReviewTask();
        t.setId(id);
        t.setRecordId(recordId);
        t.setStatus("pending");
        t.setIssueType("缺失字段");
        t.setScore(85);
        t.setOrgId(GROUP);
        t.setIsObsolete(0);
        t.setCreateTime(LocalDateTime.now().minusDays(1));
        return t;
    }

    @Test
    void keepPendingWhenStillNotQualified() {
        // 中药用了「桂枝」，与默认规则「脾肾阳虚-中药」（期望附子/肉桂）不符 → 逻辑冲突 → 待复核
        Record r = record("rec-1", false, "桂枝");
        ReviewTask t = task("task-1", "rec-1");
        when(recordMapper.selectById("rec-1")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(t));

        ReviewResultVO vo = service.review("rec-1", null);

        assertNotNull(vo);
        assertEquals("待复核", vo.getStatus());
        // 100 - 逻辑冲突 10 - 术语未标准化 4（4 个实体无 normLevel）= 86
        assertEquals(86, vo.getScore());
        assertEquals("pending", t.getStatus());
        assertTrue(vo.getErrors().stream().anyMatch(e -> "逻辑冲突".equals(e.getType())));
    }

    @Test
    void completeWhenCorrectedBecomesQualified() {
        Record r = record("rec-2", false, "桂枝");
        ReviewTask t = task("task-2", "rec-2");
        when(recordMapper.selectById("rec-2")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of(t));

        // 修正：把中药改成规则期望的附子 → 冲突消失 → 96 合格 → 任务完成
        Map<String, Object> corrected = new ObjectMapper().readValue(structured(false, "附子"), Map.class);
        ReviewDTO dto = new ReviewDTO();
        dto.setCorrectedData(corrected);

        ReviewResultVO vo = service.review("rec-2", dto);

        assertNotNull(vo);
        assertEquals("已完成", vo.getStatus());
        // 100 —— 人工修正**不再反被扣分**。
        // 原期望值是 96，注释写着「100 - 术语未标准化 4」：复核员新输入/改写的词只带
        // content、没有 normLevel，QcScorer.countUnnormalized 就把它们算作未标准化。
        // 也就是说「越认真修正、分数越低」。现在写回前统一跑一遍归一，口径与模型
        // 抽取一致，所以这 4 分不再出现。旧期望值实际固化了这个缺陷。
        assertEquals(100, vo.getScore());
        assertEquals("completed", t.getStatus());
        assertNotNull(t.getReviewedBy());
        assertNotNull(t.getCompletedTime());
    }

    /** 无待复核任务时抛统一错误码，而不是返回 null 让前端拿到 data:null（B8-4） */
    @Test
    void noActiveTaskThrowsUnifiedErrorCode() {
        when(recordMapper.selectById("rec-3")).thenReturn(record("rec-3", true));
        when(reviewTaskMapper.selectList(ArgumentMatchers.any())).thenReturn(List.of());

        ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                () -> service.review("rec-3", null));
        assertEquals(1006, e.getCode(), "「资源不存在」只保留一条码：1006");
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

        ReviewTasksVO vo = service.listTasks(1, 10, "待复核", null);

        assertEquals(1, vo.getTotal());
        ReviewTaskVO first = vo.getTasks().get(0);
        assertEquals("待复核", first.getStatus());
        assertTrue(first.isOverdue());
    }

    /** 读时指纹对不上：说明有人先改过 → 409，且一个字节都不许写（批次 25.15） */
    @Test
    void staleFingerprintIsRejectedWithConflict() {
        Record r = record("rec-5", false, "桂枝");
        when(recordMapper.selectById("rec-5")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(task("task-5", "rec-5")));

        // 指纹取自「另一个版本」：模拟本次读取之后库里内容已变
        ReviewDTO dto = new ReviewDTO();
        dto.setCorrectedData(new ObjectMapper().readValue(structured(false, "附子"), Map.class));
        dto.setFingerprint(ReviewTaskUtil.fingerprint(structured(true, "肉桂"), 100, "合格"));

        assertThrows(ConcurrentOperationException.class, () -> service.review("rec-5", dto));
        // 冲突必须发生在写库之前，不能出现「结构数据写了、评分没写」的半截状态
        Mockito.verify(recordMapper, Mockito.never())
                .updateStructuredData(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
        Mockito.verify(recordMapper, Mockito.never())
                .updateScoreFields(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** 指纹一致：正常按修正后的数据复核（不因新增校验而误伤） */
    @Test
    void matchingFingerprintPasses() {
        Record r = record("rec-6", false, "桂枝");
        when(recordMapper.selectById("rec-6")).thenReturn(r);
        when(reviewTaskMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(task("task-6", "rec-6")));

        ReviewDTO dto = new ReviewDTO();
        dto.setCorrectedData(new ObjectMapper().readValue(structured(false, "附子"), Map.class));
        dto.setFingerprint(ReviewTaskUtil.fingerprint(
                r.getStructuredData(), r.getScore(), r.getGrade()));

        ReviewResultVO vo = service.review("rec-6", dto);

        assertNotNull(vo);
        assertEquals("已完成", vo.getStatus());
    }

    /**
     * 批次 26.e 定案 A：归一在事务外，ES 失败时写库段一步都不进 —— 零写入。
     *
     * <p>这条同时钉住「归一没有被挪进事务」：只有入口先调归一、后委托，
     * 才会在抛异常时连一次 DB 读都没发生。</p>
     */
    @Test
    void normalizationFailureHappensBeforeAnyDatabaseWork() {
        EntityNormalizer failing = Mockito.mock(EntityNormalizer.class);
        Mockito.doThrow(new IllegalStateException("ES 不可用"))
                .when(failing).normalizeMap(Mockito.anyMap(), Mockito.anyString());
        IReviewWriteService write = new ReviewWriteServiceImpl(recordMapper, reviewTaskMapper,
                new ObjectMapper(), new QcRuleStore(new ObjectMapper(), Mockito.mock(QcRuleMapper.class)));
        ReviewServiceImpl svc = new ReviewServiceImpl(recordMapper, failing, write);

        ReviewDTO dto = new ReviewDTO();
        dto.setCorrectedData(Map.of("diseases", List.of(Map.of("content", "水肿"))));

        assertThrows(IllegalStateException.class, () -> svc.review("rec-es", dto));
        Mockito.verify(recordMapper, Mockito.never()).selectById(Mockito.anyString());
        Mockito.verifyNoInteractions(reviewTaskMapper);
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
