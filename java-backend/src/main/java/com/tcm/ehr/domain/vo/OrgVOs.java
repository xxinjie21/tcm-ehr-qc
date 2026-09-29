package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 组织相关视图（批次 5 改名，出参字段同步 group -> org）。
 *
 * <p>把「我的组织」「组织列表」「成员列表」等的出参集中在这一处，
 * 避免十几个接口各自拼 Map。成员函数返回嵌套视图对象。</p>
 */
public final class OrgVOs {

    private OrgVOs() {
    }

    /** GET /api/my-org：当前用户的组织上下文 */
    @Data
    public static class MyOrgVO {
        private OrgInfo org;
        /** owner=所有者 / member=成员 / null=无组织 */
        private String myRole;
        /** 申报中的建组申请；无则为 null */
        private PendingApplication pendingApplication;
    }

    /** 组织概要（列表 / 我的组织共用） */
    @Data
    public static class OrgInfo {
        private String id;
        private String code;
        private String name;
        private String status;
        private String ownerName;
        private int memberCount;
        private String appliedBy;
        private LocalDateTime createTime;
        private String rejectReason;
    }

    /** 组织的成员行 */
    @Data
    public static class MemberInfo {
        private String userId;
        private String username;
        private String role;
        private LocalDateTime joinTime;
    }

    /** 审批中的申请（申请人自见） */
    @Data
    public static class PendingApplication {
        private String code;
        private String name;
        private String status;
        private String rejectReason;
    }

    /** GET /api/orgs 出参 */
    @Data
    public static class OrgListVO {
        private List<OrgInfo> orgs = new ArrayList<>();
    }

    /** GET /api/orgs/{id}/members 出参 */
    @Data
    public static class MemberListVO {
        private List<MemberInfo> members = new ArrayList<>();
    }

    /** GET /api/orgs/pending-users 出参 */
    @Data
    public static class PendingUserVO {
        private String id;
        private String username;
        private LocalDateTime createTime;
    }
}