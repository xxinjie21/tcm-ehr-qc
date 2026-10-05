package com.tcm.ehr.domain.vo;

import lombok.Data;

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
