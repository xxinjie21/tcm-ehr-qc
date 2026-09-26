package com.tcm.ehr.common.exception;

/**
 * 资源不存在（如病历 ID 无效） → HTTP 404 + 业务错误码（默认 1006）。
 */
public class ResourceNotFoundException extends RuntimeException {

    private final int code;

    public ResourceNotFoundException(int code, String message) {
        super(message);
        this.code = code;
    }

    public ResourceNotFoundException(String message) {
        this(1006, message);
    }

    /** 业务错误码（默认 1006），由 GlobalExceptionHandler 写进 Result.code。 */
    public int getCode() {
        return code;
    }
}
