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

    /**
     * 是否跳过术语归一（true=跳过；false=执行，默认）。
     *
     * <p>术语归一是清洗五步里最耗时的一步（逐条查 ES）。清洗的目的不总是归一时
     * （如只想跑去重 / 字段清理 / 脏数据隔离），勾上此项可显著缩短耗时。</p>
     *
     * <p>注意：跳过归一后 structured_data 的 normLevel 保持原样，导出件也不会产生
     * 新的「原文→标准词」替换。用 Boolean 而非 boolean 以区分「未传」（按 false 处理，
     * 与旧调用方行为一致）与显式传值。</p>
     */
    private Boolean skipNormalize;
}
