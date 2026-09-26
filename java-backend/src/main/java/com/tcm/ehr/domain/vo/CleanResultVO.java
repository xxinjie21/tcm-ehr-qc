package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 数据清洗结果。
 */
@Data
public class CleanResultVO {

    private int deduped;
    private int repaired;
    private int cleared;
    private int isolated;
    /** 归一命中实体总数 */
    private int normalized;
    private int total;
    /** 三级命中分布：精确 / 包含 / 模糊 */
    private NormByLevel normByLevel = new NormByLevel();

    @Data
    public static class NormByLevel {
        private int exact;
        private int contain;
        private int fuzzy;
    }
}
