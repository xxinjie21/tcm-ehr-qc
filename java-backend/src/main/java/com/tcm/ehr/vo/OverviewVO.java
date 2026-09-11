package com.tcm.ehr.vo;

import lombok.Data;

@Data
public class OverviewVO {

    private long totalRecords;
    private long qualifiedCount;
    private double qualifiedRate;
    private long pendingReviewCount;
    private long invalidCount;
}
