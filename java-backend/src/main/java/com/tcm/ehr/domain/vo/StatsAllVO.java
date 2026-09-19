package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 首页看板一次拉取（出参，对应 openapi StatsAllVO）：
 * 指标卡 + 4 类统计，消除 5 个并发请求。
 */
@Data
public class StatsAllVO {

    private OverviewVO overview;
    private StatsVO disease;
    private StatsVO symptom;
    private StatsVO pattern;
    private StatsVO prescription;
}
