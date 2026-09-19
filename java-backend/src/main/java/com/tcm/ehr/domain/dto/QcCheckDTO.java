package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * 事前质控检查请求（批B·2.3，对应 openapi QcCheckDTO）。
 */
@Data
public class QcCheckDTO {

    private String recordId;
    private Object structuredData;
}
