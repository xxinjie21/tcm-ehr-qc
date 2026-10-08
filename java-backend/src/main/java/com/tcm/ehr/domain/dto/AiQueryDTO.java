package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 解读/问答/复核请求。
 *
 * <ul>
 *   <li>interpret：{@code recordId} 必填；</li>
 *   <li>chat：{@code question} 必填，{@code recordId} 可选（"这份病历…"类问题）；</li>
 *   <li>review：{@code recordId} + 修正数据。</li>
 * </ul>
 */
@Data
public class AiQueryDTO {

    /** 病历ID（interpret 必填；chat 可选） */
    private String recordId;

    /**
     * 使用者业务问题（<b>仅 chat 必填</b>）。
     *
     * <p>⚠️ 这里<b>不能</b>加 {@code @NotBlank}：本 DTO 被 interpret / chat / review
     * <b>三个端点共用</b>，而 interpret（AI 预检）与 review（AI 复核）只传 recordId，
     * 不传 question。加上 @NotBlank 会让这两个端点在进入 Controller 之前就被
     * Bean Validation 拦掉，页面上反复弹「请输入你的问题」——
     * 而用户根本没问过问题，他点的是「AI 预检」。</p>
     *
     * <p>「某个端点必填」的约束属于<b>该端点</b>，放在共用 DTO 上必然误伤其它端点。
     * chat 的必填由 {@code AiServiceImpl} 在服务层校验（抛 IllegalArgumentException，
     * 同样返回 400 + 同样的提示）。</p>
     */
    @Size(max = 2000, message = "问题最长 2000 字")
    private String question;

    /** 追问上下文：本会话近期问答（可选，前端拼好后传入；仅用于 chat） */
    @Size(max = 8000, message = "上文最长 8000 字")
    private String history;

    /**
     * 待规范的术语原文（<b>仅 termsuggest 必填</b>）。
     *
     * <p>同样<b>不能</b>加 {@code @NotEmpty}：本 DTO 被四个端点共用，加上会让
     * interpret / chat / review 在进入 Controller 之前就被 Bean Validation 拦掉
     * —— 与 {@link #question} 是同一个坑。termsuggest 的必填在 Controller 里校验。</p>
     */
    @Size(max = 50, message = "单次最多 50 个待补词")
    private List<String> terms;

    /**
     * 术语类型（仅 termsuggest；如 symptom / disease / pattern）。
     *
     * <p>留空按 symptom —— 质量报告的「待补词」清单恒来自症状类
     * （{@code StandardizationReportServiceImpl.unmatched()} 只遍历 structured_data 的 symptoms）。</p>
     */
    private String termType;
}
