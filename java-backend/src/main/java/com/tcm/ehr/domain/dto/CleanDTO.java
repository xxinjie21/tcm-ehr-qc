package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class CleanDTO {

    /** 待清洗病历ID，空=按 filters（或全量） */
    private List<String> recordIds;

    /** 范围过滤（批B·4.1）：科室/时间/证候/分级；空=全部 */
    private FiltersDTO filters;
}
