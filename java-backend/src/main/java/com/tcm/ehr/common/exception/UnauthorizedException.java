package com.tcm.ehr.common.exception;

/**
 * 未认证/凭证不可用：账号被停用、token 失效等。
 *
 * <p>与 {@link ForbiddenException}（已认证但无权限，行级数据域越权）分开的理由：
 * 前者要让用户重新登录，后者不该 —— 语义混用会让前端把"账号被停用"当成"权限不足"，
 * 提示用户去做无效的重新登录尝试。</p>
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
