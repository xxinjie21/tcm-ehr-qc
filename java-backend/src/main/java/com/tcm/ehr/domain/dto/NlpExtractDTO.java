package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * NLP 抽取请求（批G·8.1，对应 openapi NlpExtractDTO）。
 */
@Data
public class NlpExtractDTO {

    private String text;
}
