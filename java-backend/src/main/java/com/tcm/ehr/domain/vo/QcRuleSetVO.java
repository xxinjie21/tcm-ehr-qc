package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质控评分标准（出参，批P·只读下发，单一数据源）。
 *
 * <p>把 {@code QcScorer}/{@code LogicChecker} 里写死的口径下发前端做"标准可视化"，
 * 避免前端再写一份导致漂移。仅展示，不参与判定。</p>
 */
@Data
public class QcRuleSetVO {

    /** 核心要素（症状/证候/舌象/脉象/中药） */
    private List<String> coreFields = new ArrayList<>();

    /** 扣分权重：fullMissing / partialMissing / logicConflict / format / duplicate */
    private Map<String, Integer> weights = new LinkedHashMap<>();

    /** 阈值：qualified / invalid / seriousFullMissing */
    private Map<String, Integer> thresholds = new LinkedHashMap<>();

    /** 逻辑规则（证候 → 合法治法 / 方剂） */
    private List<LogicRule> logicRules = new ArrayList<>();

    /** 舌脉冲突 */
    private List<TonguePulse> tonguePulseConflicts = new ArrayList<>();

    @Data
    public static class LogicRule {
        private String pattern;
        private List<String> treatments = new ArrayList<>();
        private List<String> formulas = new ArrayList<>();
    }

    @Data
    public static class TonguePulse {
        private String tongue;
        private String pulse;
    }
}
