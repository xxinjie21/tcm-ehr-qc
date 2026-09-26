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
}
