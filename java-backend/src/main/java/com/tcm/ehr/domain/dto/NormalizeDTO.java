package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NormalizeDTO {

    /** disease/pattern/symptom/herb/formula */
    private String type;

    /** 待归一术语；空/全空白由 Bean Validation 直接拦成 400（批次 26.5）。type 是否合法仍由 Controller 判（4001）。 */
    @NotBlank(message = "请输入术语")
    private String term;
}
