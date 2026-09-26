package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 病历修改请求：仅允许修改结构化数据；原始 21 字段只读。
 */
@Data
public class UpdateRecordDTO {

    private Object structuredData;
}
