package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 导入任务（批F·7.1，对应 openapi ImportTaskVO）：创建任务后返回 taskId + 本轮摘要。
 */
@Data
public class ImportTaskVO {

    private String taskId;
    private ImportSummaryVO summary;
    /** 勾选"导入后自动解析"且抽取已开启时，返回后台批任务ID；否则为 null */
    private String autoExtractTaskId;
}
