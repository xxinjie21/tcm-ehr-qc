package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * NLP 抽取请求。
 */
@Data
public class NlpExtractDTO {

    /** 待抽取文本；空/全空白由 Bean Validation 直接拦成 400（批次 26.5）。不设长度上限，与现有服务端守卫一致。 */
    @NotBlank(message = "请输入待抽取文本")
    private String text;
}
