package com.tcm.ehr.domain.dto;

import lombok.Data;

@Data
public class NormalizeDTO {

    /** disease/pattern/symptom/herb/formula */
    private String type;

    private String term;
}
