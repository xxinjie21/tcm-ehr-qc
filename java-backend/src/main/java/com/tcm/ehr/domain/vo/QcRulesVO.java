package com.tcm.ehr.domain.vo;

import com.tcm.ehr.common.config.QcRuleSet;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 质控规则读/写响应（批Q）：规则集 + 加载告警。 */
@Data
public class QcRulesVO {

    private QcRuleSet rules;
    private List<String> warnings = new ArrayList<>();

    public QcRulesVO() {
    }

    public QcRulesVO(QcRuleSet rules, List<String> warnings) {
        this.rules = rules;
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }
}
