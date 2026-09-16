package com.tcm.ehr.domain.vo;

import lombok.Data;

@Data
public class CleanResultVO {

    private int deduped;
    private int repaired;
    private int cleared;
    private int isolated;
    /** 术语归一：content被替换为标准词的实体数 */
    private int normalized;
    private int total;
}
