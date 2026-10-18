package com.tcm.ehr.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 导出替换的安全性（E1~E4）。全部用例取自 2026-10-18 实测导出件里被改坏的原文与应有的结果。
 */
@DisplayName("导出标准化：替换必须限定长度、位置与语境")
class GovernanceExportReplaceSafetyTest {

    private GovernanceServiceImpl svc() {
        return new GovernanceServiceImpl(null, null, null, null);
    }

    private String standardize(String text, Map<String, String> pairs) {
        GovernanceServiceImpl svc = svc();
        List<Map.Entry<String, String>> list = new java.util.ArrayList<>(pairs.entrySet());
        return (String) ReflectionTestUtils.invokeMethod(svc, "standardizeText", text, list);
    }

    @Test
    void singleCharPairsAreDropped() {
        assertEquals("无脚气，黄汗", standardize("无脚气，黄汗",
                Map.of("脚气", "干脚气", "有", "有汗", "血", "血分证")),
                "单字段的替换对一律不用");
    }

    @Test
    void negatedOccurrenceIsSkipped() {
        assertEquals("无脚气", standardize("无脚气", Map.of("脚气", "干脚气")));
        assertEquals("无黄汗", standardize("无黄汗", Map.of("黄汗", "黄汗症")));
    }

    @Test
    void longestMatchWinsAndDoesNotOverlap() {
        // 先长后短 + 不重叠：不能出现「标准柴胡名+桂枝汤」这种被切掉一半的结果
        String out = standardize("柴胡桂枝汤", Map.of("柴胡", "北柴胡", "柴胡桂枝汤", "柴胡桂枝汤方"));
        assertEquals("柴胡桂枝汤方", out);
    }

    @Test
    void realReplacementsStillHappen() {
        assertEquals("脉弦细，湿热下注证",
                standardize("脉较细弦，湿热下注",
                        Map.of("脉较细弦", "脉弦细", "湿热下注", "湿热下注证")));
    }

    @Test
    void negationDoesNotBleedAcrossClauses() {
        // 2026-10-18 实测 bug：上句「血行不畅」的「不」把本句「湿热下注」误判成否定 → 漏替换
        assertEquals("湿热下注证，血行不畅；湿热下注证，血行不畅；湿热下注证，血行不畅",
                standardize("湿热下注，血行不畅；湿热下注，血行不畅；湿热下注，血行不畅",
                        Map.of("湿热下注", "湿热下注证")),
                "同一字段里同一术语必须逐处一致替换");
        // 「欠流利」的「欠」不是否定语素
        assertEquals("脉弦，欠流利。；脉弦",
                standardize("脉较弦，欠流利。；脉较弦", Map.of("脉较弦", "脉弦")),
                "「欠X」不是否定语境");
    }

    @Test
    void normativeBuIsNotNegationButRealNegationIs() {
        // 规范说法「大便不畅」的「不」（尾字属保留集）不否定同分句后面的术语
        assertEquals("大便不畅而脉弦细证", standardize("大便不畅而脉弦细", Map.of("脉弦细", "脉弦细证")));
        // 真否定：不恶寒 里的「恶寒」不替换
        assertEquals("无汗，不恶寒", standardize("无汗，不恶寒", Map.of("恶寒", "恶寒证")));
    }
}
