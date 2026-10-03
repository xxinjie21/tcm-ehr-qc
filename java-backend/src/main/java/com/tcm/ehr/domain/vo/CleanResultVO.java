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
    /**
     * 因含人工修改而**跳过归一**的病历数（批次 15，方案 A）。
     *
     * <p>清洗会重跑标准化，把人工改成非标准词的术语又归一回标准词 ——
     * 等于清洗一次就撤销一次人工修正，所以人工成果优先、被跳过的条数单列出来，
     * 免得「归一数突然变少」看起来像清洗出错。</p>
     */
    private int manualSkipped;
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
