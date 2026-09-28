package com.tcm.ehr.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 课题组相关视图（阶段2 R5）。
 *
 * <p>把「我的组」「组列表」「成员列表」等的出参集中在这一处，
 * 避免 13 个接口各自拼 Map。成员函数返回嵌套视图对象。</p>
 */
public final class GroupVOs {

    private GroupVOs() {
    }

    /** GET /api/my-group：当前用户的组上下文 */
    @Data
    public static class MyGroupVO {
        private GroupInfo group;
        /** owner=组长 / member=组员 / null=无组 */
        private String myRole;
        /** 申报中的建组申请；无则为 null */
        private PendingApplication pendingApplication;
    }

    /** 组概要（列表 / 我的组共用） */
    @Data
    public static class GroupInfo {
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

    /** 组的成员行 */
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

    /** GET /api/groups 出参 */
    @Data
    public static class GroupListVO {
        private List<GroupInfo> groups = new ArrayList<>();
    }

    /** GET /api/groups/{id}/members 出参 */
    @Data
    public static class MemberListVO {
        private List<MemberInfo> members = new ArrayList<>();
    }

    /** GET /api/groups/pending-users 出参 */
    @Data
    public static class PendingUserVO {
        private String id;
        private String username;
        private LocalDateTime createTime;
    }
}