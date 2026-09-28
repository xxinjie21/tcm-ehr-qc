package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.po.ResearchGroup;

/**
 * 课题组服务（阶段2）。
 *
 * <p>R2 先落「身份解析」这一小块 —— {@link #resolvePrimaryGroup(String)} 是
 * {@code JwtInterceptor} 每请求调用的入口，也是 R3 数据域过滤的地基。
 * 成员管理 / 审批等对外能力在 R5 追加。</p>
 */
public interface IGroupService extends IService<ResearchGroup> {

    /**
     * 解析某用户当前生效的主组（含组内角色）。
     *
     * @param userId 用户 ID
     * @return 主组成员行；无组 / 组已停用 / DB 异常时返回 {@code null}（<b>不是抛异常</b>）
     */
    GroupResolution resolvePrimaryGroup(String userId);
}
