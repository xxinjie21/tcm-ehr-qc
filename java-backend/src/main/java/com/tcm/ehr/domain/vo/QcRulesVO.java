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
