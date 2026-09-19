package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 复核任务列表（出参，批D·5.1，对应 openapi ReviewTasksVO）。
 */
@Data
public class ReviewTasksVO {

    private long total;
    private List<ReviewTaskVO> tasks = new ArrayList<>();
}
