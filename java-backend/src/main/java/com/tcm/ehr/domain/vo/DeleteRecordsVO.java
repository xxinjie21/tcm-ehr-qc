package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * 批量删除响应（批F·7.3）。
 */
@Data
public class DeleteRecordsVO {

    private int deletedCount;
}
