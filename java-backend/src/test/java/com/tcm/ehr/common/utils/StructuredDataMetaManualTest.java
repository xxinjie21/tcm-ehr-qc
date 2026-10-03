package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化数据的「人工修改」标记（批次 15 工作项 4）。
 *
 * <p>要锁住三件事：<b>①</b> 人工路径打标记；<b>②</b> 自动路径（清洗归一）**不打**
 * —— 否则模型准确率统计会把自动结果误当人工；<b>③</b> 人工路径<b>替换</b>而非叠加
 * {@code _meta}，因为人工改过之后旧版本戳本就失效，继续显示「依据某一版词典」是误导。</p>
 */
class StructuredDataMetaManualTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String sample() {
        return "{\"diseases\":[{\"content\":\"胃痛\"}],\"_meta\":{"
                + "\"dictVersion\":\"pattern:abc12345;\",\"dictTermCount\":3587,"
                + "\"dictCapturedAt\":\"2026-10-02 10:00:00\"}}";
    }

    @Test
    @DisplayName("人工修改：打上标记，并带上操作人与时间")
    void manualStampAddsMarker() {
        String out = StructuredDataMeta.stampManual(mapper, sample(), "alice");
        assertTrue(StructuredDataMeta.isManuallyEdited(mapper, out), "人工修改后必须能读出标记");
        assertTrue(out.contains("\"manuallyEdited\":true"), out);
        assertTrue(out.contains("alice"), "应记录操作人: " + out);
    }

    @Test
    @DisplayName("人工修改会替换旧版本戳 —— 改过之后再说「依据某版词典」是误导")
    void manualStampReplacesStaleVersion() {
        String out = StructuredDataMeta.stampManual(mapper, sample(), "alice");
        assertFalse(out.contains("abc12345"),
                "人工改过之后旧的 dictVersion 不该继续保留: " + out);
        assertFalse(out.contains("3587"), "词条数同样属于旧版本信息，不该保留: " + out);
    }

    @Test
    @DisplayName("自动流程（清洗归一）：只打版本、不打人工标记")
    void autoStampDoesNotMarkManual() {
        String out = StructuredDataMeta.stamp(mapper, sample(), "pattern:zzz99999;", 3587);
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper, out),
                "清洗归一是自动流程，打人工标记会让准确率统计失真");
        assertTrue(out.contains("zzz99999"), "自动流程仍应记录版本: " + out);
    }

    @Test
    @DisplayName("未标记 / 空 / 坏 JSON：都读作「非人工修改」（宁可漏标，不可误标）")
    void unmarkedOrBrokenReadsFalse() {
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper,
                "{\"diseases\":[]}"));
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper, ""));
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper, null));
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper, "这不是 JSON"));
    }

    @Test
    @DisplayName("标记写成 1 / \"true\" 也认（不同序列化器可能写出数字或字符串）")
    void tolerantOfNumericOrStringTrue() {
        assertTrue(StructuredDataMeta.isManuallyEdited(mapper,
                "{\"_meta\":{\"manuallyEdited\":1}}"));
        assertTrue(StructuredDataMeta.isManuallyEdited(mapper,
                "{\"_meta\":{\"manuallyEdited\":\"true\"}}"));
        assertFalse(StructuredDataMeta.isManuallyEdited(mapper,
                "{\"_meta\":{\"manuallyEdited\":0}}"));
    }
}