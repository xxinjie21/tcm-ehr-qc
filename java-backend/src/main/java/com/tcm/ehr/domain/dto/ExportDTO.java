package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ExportDTO {

    /** csv / json */
    private String format;

    /** 筛选条件：department / start / end */
    private Map<String, Object> filters;
}
