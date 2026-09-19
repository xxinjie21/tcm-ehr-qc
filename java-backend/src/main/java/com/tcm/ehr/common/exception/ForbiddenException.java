package com.tcm.ehr.common.exception;

/**
 * 行级数据域越权（批F·7.2/7.4）：功能权限之外的数据行权限校验失败，
 * 由 GlobalExceptionHandler 统一转 HTTP 403 + Result{code=403}。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
