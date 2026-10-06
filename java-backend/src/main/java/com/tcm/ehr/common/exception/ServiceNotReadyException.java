package com.tcm.ehr.common.exception;

/**
 * 服务未就绪（缺少必要配置）-> HTTP 503 + code=1011。
 *
 * 典型触发：未配置 TCM_LLM_ENC_KEY 就保存 LLM 密钥；互斥锁服务（Redis）不可用（批次 16.1）。
 * 此时服务本身是活的、
 * 重启也不能自愈，缺的是一条配置；消息已写明要配什么，可直接展示给管理员。
 *
 * 为何是 503 + 1011 而不是兜底 500：兜底分支故意不回显异常原文（只给追踪码），
 * 而这句「请配置 TCM_LLM_ENC_KEY」正是用户唯一能照着做的信息，落兜底等于把可操作提示丢掉。
 * 503 与 1010（术语索引不可用）同族：都是「依赖/配置没到位，稍后或配置后重试」。
 */
public class ServiceNotReadyException extends RuntimeException {

    public ServiceNotReadyException(String message) {
        super(message);
    }
}
