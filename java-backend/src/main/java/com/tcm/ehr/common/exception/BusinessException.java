package com.tcm.ehr.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常（P3.6）：携带业务错误码 + 可展示文案 + HTTP 状态。
 *
 * <p>此前 Controller 里直接拼 {@code ResponseEntity.badRequest().body(Result.error(code, msg))}
 * 返回错误体（如 {@code GovernanceController} 的 4001 术语类型非法、2001 导出被拒），
 * 错误码与状态只出现在那一处、外部无法统一审计。改为抛本异常，由
 * {@link GlobalExceptionHandler} 统一出口，与既有的
 * {@link IllegalStateException} / {@link ResourceNotFoundException} 等保持同一处理风格。</p>
 */
public class BusinessException extends RuntimeException {

    private final int code;
    private final HttpStatus status;

    /**
     * @param code    业务错误码（写进 Result.code）
     * @param message 可展示文案
     * @param status  HTTP 状态
     */
    public BusinessException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /** 默认 HTTP 400 的便捷构造（绝大多数业务参数错误） */
    public BusinessException(int code, String message) {
        this(code, message, HttpStatus.BAD_REQUEST);
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
