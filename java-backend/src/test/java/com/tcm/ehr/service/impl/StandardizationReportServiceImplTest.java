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

    @Test
    @DisplayName("未归一实体被拆成四类，且 dictionaryGap 只归词表侧")
    void unmatchedSplitIntoFourBuckets() {
        givenRecords("{\"symptoms\":["
                + entity("双", "双", false) + ","                    // 碎片（1 字，无主）
                + entity("脉细数", "脉细数", false) + ","            // 分类错放
                + entity("腹部压痛", "腹部压痛", false) + ","        // 体征错放
                + entity("神疲乏力", "神疲乏力", false) + ","        // 词表缺口
                + entity("发热", "发热", true) + "]}");               // 已归一，不计入

        StandardizationReportVO.UnmatchedBreakdown b = svc.report().getUnmatched();

        assertEquals(4, b.getTotal(), "只统计未归一的 4 条，已归一的发热不计入");
        assertEquals(1, b.getFragment(), "「双」孤字 → 抽取碎片");
        assertEquals(1, b.getMisrouted(), "「脉细数」以脉开头 → 分类错放");
        assertEquals(1, b.getPhysicalSign(), "「腹部压痛」含压痛 → 体征错放");
        assertEquals(1, b.getDictionaryGap(), "「神疲乏力」是标准词但词表没有 → 词表缺口");
    }

    @Test
    @DisplayName("两字体征「压痛」归体征错放，不因字少被误判成抽取碎片")
    void twoCharPhysicalSignIsNotFragment() {
        // 「压痛」只有 2 个字，按「长度 ≤ 2 = 碎片」判会归错责：
        // 它其实被完整抽出来了，只是放错了数组，该修的是分类路由而非抽取截断
        givenRecords("{\"symptoms\":[" + entity("压痛", "压痛", false) + "]}");

        StandardizationReportVO.UnmatchedBreakdown b = svc.report().getUnmatched();

        assertEquals(1, b.getPhysicalSign(), "「压痛」是具名体征，应归体征错放");
        assertEquals(0, b.getFragment(), "不应因字少被误判成抽取碎片");
    }

    @Test
    @DisplayName("体征优先于脉/舌判定（压痛不会被误归为分类错放）")
    void physicalSignBeatsMisroute() {
        givenRecords("{\"symptoms\":["
                + entity("脉压痛", "脉压痛", false) + "]}");

        StandardizationReportVO.UnmatchedBreakdown b = svc.report().getUnmatched();

        assertEquals(1, b.getPhysicalSign(), "含「压痛」应归体征错放");
        assertEquals(0, b.getMisrouted(), "不应同时计入分类错放");
    }

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
}