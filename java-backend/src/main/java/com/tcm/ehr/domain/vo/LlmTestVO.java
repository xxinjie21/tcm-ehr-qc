package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * LLM 连通性探测结果（UX-68，{@code POST /api/llm/test}）。
 */
@Data
public class LlmTestVO {

    private String provider;

    /** 实际使用的模型名；配置留空时为通道默认模型 */
    private String model;

    /** 往返耗时（毫秒） */
    private long latencyMs;

    /** 模型回复内容（探测提示词为 ping，回复通常很短） */
    private String reply;
}
