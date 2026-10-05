package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.DictionaryTerm;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.StandardizationReportVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.DictionaryTermStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标准化质量报告的分类逻辑测试（批次 24）。
 *
 *
 * 报告的价值全在「把未归一拆成四类并各自归责」上：碎片/错放是抽取侧的责任，
 *
 * dictionaryGap 才是补词表能解决的。混在一起看会误判成「词表不够」，
 * 于是把力气花在对的地方 —— 这是本测试要守住的核心。
 */
class StandardizationReportServiceImplTest {

    private RecordMapper recordMapper;
    private StandardizationReportServiceImpl svc;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        // 词表读路径 mock 成空，让本测试专注乙类的四类拆分；
        // 甲类词典质量依赖真实词表，不在本测试范围
        DictionaryTermStore termStore = mock(DictionaryTermStore.class);
        when(termStore.readEffective(any(), any())).thenReturn(List.of());
        svc = new StandardizationReportServiceImpl(recordMapper, termStore,
                new tools.jackson.databind.ObjectMapper());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static String entity(String content, String sourceText, boolean normalized) {
        String norm = normalized ? ",\"normLevel\":1" : "";
        return "{\"content\":\"" + content + "\",\"sourceText\":\"" + sourceText + "\"" + norm + "}";
    }

    private void givenRecords(String structuredData) {
        Record r = new Record();
        r.setId("r1");
        r.setOrgId("org-A");
        r.setScore(95);
        r.setStructuredData(structuredData);
        r.setQcResults("{\"score\":95,\"deductions\":[{\"type\":\"术语未标准化\",\"points\":5}]}");
        r.setChiefComplaint("失眠3月");
        r.setSelfReport("我这两天睡不着，浑身没力气");
        when(recordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(r)));
    }

    /** 造一条带接诊时间的病历，用于时间维度测试 */
    private static Record recordAt(String id, String visitTime) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId("org-A");
        r.setScore(95);
        r.setStructuredData("{\"symptoms\":[]}");
        r.setQcResults("{\"score\":95,\"deductions\":[]}");
        r.setVisitTime(java.time.LocalDateTime.parse(visitTime.replace(" ", "T")));
        return r;
    }

    private void givenTimedRecords(Record... records) {
        when(recordMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(records)));
    }

    // 说明（批次12 · 12d）：原先这里有三个用例，锁的是**未归一的分类规则**本身 ——
    //   unmatchedSplitIntoFourBuckets（四类拆分）·
    //   twoCharPhysicalSignIsNotFragment（两字体征「压痛」不因字少被误判成碎片）·
    //   physicalSignBeatsMisroute（「脉压痛」归体征而非分类错放）。
    // 分类逻辑已搬到库里用 JSON_TABLE 做（见 RecordMapper.selectUnmatchedBreakdown），
    // 这三个断言必然失效。规则没有失去覆盖：同样的输入已搬进 tools/verify-unmatched-sql.py
    // 的「规则夹具」段，用**与生产同一条 SQL** 对真库断言（含「脉压痛」那条判定顺序）。
    // 本测试类其余用例（时间维度、归一率、封顶率等）不受影响，继续保留。

    @Test
    @DisplayName("可归一实体归一率：词表里没有的标准词不进分母")
    void normalizableRateExcludesDictionaryGap() {
        // 词表为空（mock 返回空），所以「发热」也不在词表内 → 分母应为 0
        givenRecords("{\"symptoms\":["
                + entity("发热", "发热", true) + ","
                + entity("神疲乏力", "神疲乏力", false) + "]}");

        StandardizationReportVO.NormalizableRate r = svc.report().getNormalizable();

        // 词表空 → 两个词都不在词表内 → 分母 0（补词表后这里才会有数）
        assertEquals(0, r.getDenominator(),
                "词表里没有的词不算进分母 —— 它们是词表缺口本身，不是归一失败");
    }

    @Test
    @DisplayName("质控封顶率：术语未标准化扣满 5 分计为封顶")
    void cappedDetected() {
        givenRecords("{\"symptoms\":[]}");

        StandardizationReportVO.ScoreDistribution d = svc.report().getScore();

        assertEquals(1, d.getTotal());
        assertEquals(1, d.getCapped(), "扣分 5 分撞 cap → 封顶");
        assertEquals(95, d.getMin());
        assertEquals(95, d.getMax());
    }

    @Test
    @DisplayName("报告带免责声明（防止被当成科研级准确率外传）")
    void disclaimerPresent() {
        givenRecords("{\"symptoms\":[]}");

        StandardizationReportVO vo = svc.report();

        assertNotNull(vo.getDisclaimer());
        assertTrue(vo.getDisclaimer().contains("不代表真实病历"),
                "声明必须点明乙类不等于真实病历准确率");
        assertNotNull(vo.getGeneratedAt());
    }

    // ------------------------------------------------------------ 时间维度

    @Test
    @DisplayName("上下界都给：按接诊时间过滤，且排除数正确")
    void filtersByVisitTimeWhenBothBoundsGiven() {
        givenTimedRecords(
                recordAt("a", "2024-03-15 09:00:00"),
                recordAt("b", "2024-06-20 09:00:00"),
                recordAt("c", "2025-01-10 09:00:00"));

        StandardizationReportVO vo = svc.report("2024-01-01", "2024-12-31");

        assertEquals(2, vo.getRange().getRecords(), "只应保留 2024 年的两条");
        assertEquals(1, vo.getRange().getExcluded(), "被排除的就是 2025 那一条");
        assertEquals("2024-01-01", vo.getRange().getStart());
        assertEquals("2024-12-31", vo.getRange().getEnd());
    }

    @Test
    @DisplayName("只给一端不算区间：按「未给」处理，返回全部（这条曾写错，返回了 131 条）")
    void oneSidedRangeIsIgnored() {
        givenTimedRecords(
                recordAt("a", "2024-03-15 09:00:00"),
                recordAt("b", "2025-01-10 09:00:00"));

        // 只给 start：若被当成有效过滤，会只剩 2024 之后的部分；
        // 项目口径（与 StatsController 一致）是「两端同时给才生效」
        StandardizationReportVO vo = svc.report("2024-01-01", null);

        assertEquals(2, vo.getRange().getRecords(),
                "只给一端应视为不限，返回全部 —— 否则用户看到记录变少却找不到原因");
        assertEquals(0, vo.getRange().getExcluded());
        assertNull(vo.getRange().getStart(), "未生效的区间不应回显");
    }

    @Test
    @DisplayName("按月分组：各组条数之和等于区间内总数")
    void byMonthSumsToRange() {
        givenTimedRecords(
                recordAt("a", "2024-03-15 09:00:00"),
                recordAt("b", "2024-03-20 09:00:00"),
                recordAt("c", "2024-04-10 09:00:00"));

        StandardizationReportVO vo = svc.report(null, null);

        int sum = vo.getByMonth().stream()
                .mapToInt(StandardizationReportVO.MonthlyBucket::getRecords).sum();
        assertEquals(vo.getRange().getRecords(), sum, "按月求和必须等于总数，否则有记录被静默丢弃");
        assertEquals(2, vo.getByMonth().size(), "2024-03 与 2024-04 共两组");
        // 2024-03 有 2 条，2024-04 有 1 条 —— 分组不能把同月拆散
        StandardizationReportVO.MonthlyBucket mar = vo.getByMonth().stream()
                .filter(b -> "2024-03".equals(b.getMonth())).findFirst().orElseThrow();
        assertEquals(2, mar.getRecords());
    }

    @Test
    @DisplayName("接诊时间为空的病历不被丢弃，归入「未知」组")
    void nullVisitTimeGoesToUnknownBucket() {
        Record noTime = new Record();
        noTime.setId("x");
        noTime.setOrgId("org-A");
        noTime.setScore(95);
        noTime.setStructuredData("{\"symptoms\":[]}");
        givenTimedRecords(recordAt("a", "2024-03-15 09:00:00"), noTime);

        StandardizationReportVO vo = svc.report(null, null);

        boolean hasUnknown = vo.getByMonth().stream()
                .anyMatch(b -> "未知".equals(b.getMonth()));
        assertTrue(hasUnknown, "没有接诊时间的病历要单独成组，不能静默丢掉");
        int sum = vo.getByMonth().stream()
                .mapToInt(StandardizationReportVO.MonthlyBucket::getRecords).sum();
        assertEquals(2, sum, "未知组的记录也必须计入");
    }
}