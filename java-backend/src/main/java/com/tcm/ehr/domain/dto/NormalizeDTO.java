package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 术语归一请求。
 *
 * @param type disease/pattern/symptom/herb/formula
 * @param term 待归一术语；空/全空白由 Bean Validation 直接拦成 400（批次 26.5）。type 是否合法仍由 Controller 判（4001）。
 */
public record NormalizeDTO(String type,
                           @NotBlank(message = "请输入术语") String term) {
}
