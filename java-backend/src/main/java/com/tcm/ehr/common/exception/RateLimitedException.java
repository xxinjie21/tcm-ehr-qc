package com.tcm.ehr.common.exception;

/**
 * 触发接口限流 -> HTTP 429 + code=429。
 *
 * <p>与 {@link ConcurrentOperationException}（409）分开：409 是「同一操作正在并发进行」，
 * 重试时机由对方决定；429 是「你这个身份在窗口内请求太多」，重试时机由窗口决定。
 * 两者对客户端的语义不同（前端提示文案也不同），不该共用一个状态码。</p>
 *
 * <p><b>为什么单列类型而不是复用 IllegalStateException</b>：全仓的
 * IllegalStateException 绝大多数是服务端自身故障（词典序列化失败、评分写入失败…），
 * 按类统一映射成 429 会把真故障说成「你请求太快了」——失真方向正好相反。</p>
 */
public class RateLimitedException extends RuntimeException {

    public RateLimitedException(String message) {
        super(message);
    }
}
