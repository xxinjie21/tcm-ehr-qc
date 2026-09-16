package com.tcm.ehr.common.exception;

/**
 * 登录认证失败异常（用户名不存在或密码错误）。
 *
 * <p>出于安全考虑，不区分“用户不存在”与“密码错误”，统一抛出本异常，
 * 由 AuthController 映射为接口契约约定的失败响应：HTTP 401 + Result.code=401。</p>
 */
public class BadCredentialsException extends RuntimeException {

    public BadCredentialsException(String message) {
        super(message);
    }
}
