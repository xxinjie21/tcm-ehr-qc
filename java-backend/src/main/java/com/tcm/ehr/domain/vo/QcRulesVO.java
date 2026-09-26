package com.tcm.ehr.domain.vo;

import com.tcm.ehr.common.config.QcRuleSet;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 质控规则读/写响应（批Q/R）：规则集 + 自然语言描述 + 目录 + 告警。 */
@Data
public class QcRulesVO {

    private QcRuleSet rules;
    /** 自然语言描述（与规则同源，供标准面板/配置弹窗直接展示） */
    private List<String> descriptions = new ArrayList<>();

    /**
     * 一致性规则的构成摘要，如「14 条（中药 9 / 方剂 5）」。
     *
     * <p>给质控标准面板那行用。<b>由规则数据拼出来、不由前端写死</b>：前端原来写的是
     * 「14 条（证候 → 中药 / 舌象 / 脉象）」，而实际规则里根本没有舌象/脉象，
     * 改规则也不会改文案（审查报告 H2）。</p>
     */
    private String consistencySummary = "";
    /** 可选要素目录（用户只选中文名） */
    private List<QcRuleSet.Element> catalogElements = new ArrayList<>();
    /** 格式模板目录 */
    private List<QcRuleSet.FormatRule> catalogFormats = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public QcRulesVO() {
    }

    public QcRulesVO(QcRuleSet rules, List<String> warnings) {
        this.rules = rules;
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }
}
