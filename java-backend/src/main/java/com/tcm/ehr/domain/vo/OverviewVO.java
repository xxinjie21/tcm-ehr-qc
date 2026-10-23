package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 看板总览指标。
 *
 * 五个数字同源同口径（同一批病历、同一时刻计算），供首页卡片直接渲染。
 *
 * ⚠️ sourceVersion 标识这批数字出自哪一版词典。换词表后数字会变，
 * 跨版本的两组数字不可直接比较，需先对齐 sourceVersion。
 */
@Data
public class OverviewVO {

    private long totalRecords;
    private long qualifiedCount;
    private double qualifiedRate;
    private long pendingReviewCount;
    private long invalidCount;

    /**
     * 这批数字出自哪一版词典（批次14 · 对标 A5「派生数字可回溯血缘」）。
     *
     * <p>来源是生效词典的内容版本哈希。作用很具体：换过词表之后报告数字会变，
     * 有这一项才能回答「这个数是哪一版词表算出来的」，否则只能猜「是不是刚才那次导入导致的」。</p>
     */
    private String sourceVersion;

    /** 数据生成时刻（对标 A5 的「刷新时间」，与报告页的 generatedAt 同口径） */
    private String generatedAt;
}
