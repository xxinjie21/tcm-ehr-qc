package com.tcm.ehr.common.exception;

/**
 * 术语索引（Elasticsearch）不可用 -> HTTP 503 + code=1010。
 *
 * <p>2026-09-23 起归一<b>只认 ES 索引</b>，不再有内存词典兜底。因此 ES 不可用时不能静默降级成
 * 「词典里没收录这个词」—— 那会把「服务挂了」说成「词典没有」，用户会以为词典缺词而去做
 * 完全错误的下一步。这里显式抛出，由 {@link GlobalExceptionHandler} 映射成 503 + 人话消息。</p>
 *
 * <p>为何是 503 而不是 502/500：术语索引不可用属于「依赖服务暂时不可用」，语义上可重试；
 * 502 已留给 {@link LlmProbeException}（LLM 网关探测），500 是未知异常兜底。</p>
 */
public class TermIndexUnavailableException extends RuntimeException {

    public TermIndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
