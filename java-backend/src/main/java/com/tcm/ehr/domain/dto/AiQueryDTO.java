package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 解读/复核/术语补词请求。
 *
 * <ul>
 *   <li>interpret：recordId 必填；</li>
 *   <li>review：recordId 必填；</li>
 *   <li>termsuggest：terms 必填，termType 可选。</li>
 * </ul>
 */
@Data
public class AiQueryDTO {

    /** 病历ID（interpret / review 必填） */
    private String recordId;

    /**
     * 待规范的术语原文（仅 termsuggest 必填）。
     *
     * 不能加 {@code @NotEmpty}：本 DTO 被三个端点共用，加上会让
     * interpret / review 在进入 Controller 之前就被 Bean Validation 拦掉
     * —— 「某个端点必填」的约束属于该端点，放在共用 DTO 上必然误伤其它端点。
     * termsuggest 的必填在 Controller 里校验。
     */
    @Size(max = 50, message = "单次最多 50 个待补词")
    private List<String> terms;

    /**
     * 术语类型（仅 termsuggest；如 symptom / disease / pattern）。
     *
     * 留空按 symptom —— 质量报告的「待补词」清单恒来自症状类
     * （{@code StandardizationReportServiceImpl.unmatched()} 只遍历 structured_data 的 symptoms）。
     */
    private String termType;
}
