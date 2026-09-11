package com.tcm.ehr.dto;

import lombok.Data;

import java.util.List;

@Data
public class CleanDTO {

    /** 待清洗病历ID，空=全量 */
    private List<String> recordIds;
}
