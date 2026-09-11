package com.tcm.ehr.dto;

import lombok.Data;

@Data
public class NormalizeDTO {

    /** disease/pattern/symptom/herb/formula */
    private String type;

    private String term;
}
