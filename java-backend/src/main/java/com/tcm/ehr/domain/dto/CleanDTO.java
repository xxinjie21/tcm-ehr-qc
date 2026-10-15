package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class CleanDTO {

    /** 待清洗病历ID（应为质控合格病历），空=按 filters */
    private List<String> recordIds;

    /**
     * 范围过滤：科室/时间/证候；空=全部。
     *
     * <p>清洗只作用于「质控合格」病历，{@code filters.grade} 会被忽略（即使传了别的分级，
     * 服务端也按「合格」收窄）——见 {@code GovernanceServiceImpl#qualifiedScope}。</p>
     */
    private FiltersDTO filters;
}
