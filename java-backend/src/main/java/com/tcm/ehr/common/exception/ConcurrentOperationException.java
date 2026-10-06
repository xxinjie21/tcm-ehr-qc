package com.tcm.ehr.common.exception;

/**
 * 并发互斥未获锁（同一操作正在进行）-> HTTP 409 + code=409。
 *
 * {@link com.tcm.ehr.common.utils.DistLock} 拿不到锁时抛本异常。这不是系统故障，
 * 而是「当前状态不允许该请求」，所以既不该回兜底 500（用户会看到「系统异常（追踪码 …）」，
 * 把一句可操作的「请稍后重试」说成故障），也不该静默放行。
 *
 * 为何单列一个类型而不是用 IllegalStateException：全仓有 10+ 处
 * IllegalStateException，绝大多数是服务端自身故障（词典序列化失败、MD5 不可用、
 * 评分写入失败、provider 配置非法…）。若在 {@link GlobalExceptionHandler} 里按类统一映射成
 * 409，等于把那些真故障也说成「客户端状态冲突」——失真方向正好反过来。
 */
public class ConcurrentOperationException extends RuntimeException {

    public ConcurrentOperationException(String message) {
        super(message);
    }
}
