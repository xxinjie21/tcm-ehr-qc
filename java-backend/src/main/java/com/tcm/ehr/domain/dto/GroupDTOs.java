package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 课题组相关请求体（阶段2 R5）。
 */
public final class GroupDTOs {

    private GroupDTOs() {
    }

    /** 拒绝建组申请 */
    @Data
    public static class RejectRequest {
        @NotBlank(message = "拒绝理由不能为空")
        private String reason;
    }

    /** 更新组信息（仅名称与用途可改） */
    @Data
    public static class UpdateGroupRequest {
        private String name;
        private String purpose;
    }

    /** 组长从待分配池拉人 */
    @Data
    public static class AddMemberRequest {
        @NotBlank(message = "用户 ID 不能为空")
        private String userId;
    }

    /** 组长退出前必须指定继任者 */
    @Data
    public static class TransferOwnerRequest {
        @NotBlank(message = "新组长用户 ID 不能为空")
        private String newOwnerUserId;
    }
}