package com.tcm.ehr.common.exception;

/**
 * LLM 连通性探测失败（UX-68）。
 *
 * <p>与 {@link com.tcm.ehr.common.utils.LlmClient#chat} 的「静默降级」刻意相反：探测接口的意义
 * 就是把失败原因告诉用户，所以这里要把原因抛出来。消息在抛出前<b>已脱敏</b>
 * （抹掉 api-key 明文、截断到 300 字），可直接展示。</p>
 */
public class LlmProbeException extends RuntimeException {

    public LlmProbeException(String message) {
        super(message);
    }
}
