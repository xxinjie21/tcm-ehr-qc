package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 组织相关请求体（批次 6：自助创建 + 授权开关 + 归档/改派）。
 */
public final class OrgDTOs {

    private OrgDTOs() {
    }

    /**
     * 自助创建组织（批次 6）：任何登录用户可创建，创建者自动成为 owner。
     *
     * <p>{@code code} 可空 —— 留空则由服务端生成。给定时必须匹配编码规则：
     * 大写字母、数字、连字符，长度 2~50。这条白名单是防滥用的第一道闸
     * （否则能创建出 {@code ../} 之类的 code，日志与文件名都会被污染）。</p>
     */
    @Data
    public static class CreateOrgRequest {
        @Size(max = 100, message = "组织名称最长 100 字")
        private String name;

        @Pattern(regexp = "^$|^[A-Z0-9][A-Z0-9-]{1,49}$",
                message = "组织编码只能是大写字母、数字、连字符，长度 2~50")
        private String code;

        @Size(max = 500, message = "用途说明最长 500 字")
        private String purpose;
    }

    /** 拒绝建组申请 */
    @Data
    public static class RejectRequest {
        @NotBlank(message = "拒绝理由不能为空")
        private String reason;
    }

    /** 更新组织信息（仅名称与用途可改） */
    @Data
    public static class UpdateGroupRequest {
        @Size(max = 100, message = "组织名称最长 100 字")
        private String name;

        @Size(max = 500, message = "用途说明最长 500 字")
        private String purpose;
    }

    /**
     * 授权/回收成员的写权限（批次 6）。
     *
     * <p>两个开关<b>独立</b>：owner 可以只给「词典写」或只给「质控规则写」。
     * 字段为 {@code Boolean}（可空）—— null 表示「不改这一位」，
     * 否则前端只提交其中一个开关就会把另一个误清零。</p>
     */
    @Data
    public static class SetPermissionsRequest {
        private Boolean canWriteDictionary;
        private Boolean canWriteQcRules;
    }

    /** owner 按用户名搜索后拉人 */
    @Data
    public static class AddMemberRequest {
        @NotBlank(message = "用户 ID 不能为空")
        private String userId;
    }

    /** owner 退出前必须指定继任者；管理员改派所有者同样用它 */
    @Data
    public static class TransferOwnerRequest {
        @NotBlank(message = "新所有者用户 ID 不能为空")
        private String newOwnerUserId;
    }

    /** 归档组织（仅管理员，前提成员数为 0） */
    @Data
    public static class ArchiveOrgRequest {
        @NotBlank(message = "归档原因不能为空")
        private String reason;
    }
}