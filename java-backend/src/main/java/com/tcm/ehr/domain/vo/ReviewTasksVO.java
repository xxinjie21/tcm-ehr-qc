package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 复核任务列表。
 */
@Data
public class ReviewTasksVO {

    private long total;
    private List<ReviewTaskVO> tasks = new ArrayList<>();
    /** 因关联病历已删而跳过的任务数（P5.2：避免“任务数与列表条数对不上”的隔屏疑问） */
    private int skippedMissing;
}
