package com.tcm.ehr.common.domain;

import lombok.Data;

/**
 * 统一响应包装：接口一律返回 code / msg / data（文件流下载接口除外）。
 */
@Data
public class Result<T> {

    /** 业务状态码：200 成功；401 未登录、403 权限不足；其余见错误码表 */
    private int code;

    /** 提示信息，失败时直接展示给用户 */
    private String msg;

    /** 业务数据，失败时为 null */
    private T data;

    /**
     * 成功响应（提示语固定为 success）。
     *
     * @param data 业务数据
     * @return code=200 的响应
     */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMsg("success");
        r.setData(data);
        return r;
    }

    /**
     * 成功响应（自定义提示语）。
     *
     * @param msg  提示信息
     * @param data 业务数据
     * @return code=200 的响应
     */
    public static <T> Result<T> ok(String msg, T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }

    /**
     * 成功响应（无数据体）。
     *
     * @return code=200、data=null 的响应
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应。
     *
     * @param code 业务状态码
     * @param msg  失败原因
     * @return 按入参填写 code 与 msg 的响应
     */
    public static <T> Result<T> error(int code, String msg) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(null);
        return r;
    }

    /**
     * 失败响应（默认 500）。
     *
     * @param msg 失败原因
     * @return code=500 的响应
     */
    public static <T> Result<T> error(String msg) {
        return error(500, msg);
    }
}
