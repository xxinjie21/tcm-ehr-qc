package com.tcm.ehr.domain.dto;

import lombok.Data;

/**
 * AI 解读/问答/复核请求（入参，对应 openapi AiQueryDTO；批C 3.1/3.2，批D 复用）。
 *
 * <ul>
 *   <li>interpret：{@code recordId} 必填；</li>
 *   <li>chat：{@code question} 必填，{@code recordId} 可选（"这份病历…"类问题）；</li>
 *   <li>review（批D）：{@code recordId} + 修正数据。</li>
 * </ul>
 */
@Data
public class AiQueryDTO {

    /** 病历ID（interpret 必填；chat 可选） */
    private String recordId;

    /** 使用者业务问题（chat 必填） */
    private String question;

    /** 追问上下文：本会话近期问答（可选，前端拼好后传入；仅用于 chat） */
    private String history;
}
