package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入进度：任务状态暂存服务内存，重启后丢失（接口返回 404）。
 * status 取值 处理中 / 已完成（与 openapi 一致）。
 */
@Data
public class ImportStatusVO {

    private String taskId;
    private String status;
    private int total;
    private int processed;
    private int success;
    private int failed;
    private List<ImportSummaryVO.Failure> failures = new ArrayList<>();
}
