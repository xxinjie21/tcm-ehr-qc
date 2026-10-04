package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 词典基线更新提案的入参（批次 17）。
 *
 * 提案携带的是完整目标词典而非增量 diff：这样审核时组长直接在快照上增删改即可，
 * 合并天然是整快照替换（因此能支持删除），不必在合并时再算一次差异。
 */
public final class DictProposalDTOs {

    private DictProposalDTOs() {
    }

    /** 提交提案：一份完整的目标词典 */
    @Data
    public static class CreateProposalRequest {
        /** 术语类型；一次提案只针对一种 */
        @NotBlank(message = "请选择术语类型")
        private String type;

        /** 目标词典全量：standardTerm + aliases + code + source */
        @NotEmpty(message = "目标词典不能为空")
        private List<Map<String, Object>> terms;
    }

    /** 组长/提交者在线修改提案内容：只改提案，不动基线 */
    @Data
    public static class UpdateTermsRequest {
        @NotEmpty(message = "提案内容不能为空")
        private List<Map<String, Object>> terms;
    }

    /** 审核：通过（合并进基线 + 生成归档）/ 拒绝（填理由） */
    @Data
    public static class AuditRequest {
        /** true=通过并合并；false=拒绝 */
        private Boolean approve;

        /** 拒绝时必填；通过时可作为合并备注 */
        private String comment;
    }

    /**
     * 提案列表的查询条件。
     *
     * 聚成 DTO 而不是继续加位置参数：列表的过滤维度已经涨到 5 个
     * （组织 / 状态 / 类型 / 视角 / 提交人），再往后每个调用点都得数参数顺序。
     */
    @Data
    public static class ProposalQuery {
        /** 按组织过滤；null = 不限组织（管理员可跨组织看） */
        private String orgId;

        /** 按状态过滤；null / 空白 = 全部 */
        private String status;

        /** 按术语类型过滤；null / 空白 = 全部类型 */
        private String type;

        /** true = 组长视角（看全组）；false = 成员视角（只看自己提交的） */
        private Boolean isOwner;

        /** 成员视角下「我」的提交人标识；与 submit 写入 proposal.submitUserId 的是同一个值 */
        private String submitUserId;

        /** 是否按提交人过滤：成员视角必须为 true，否则能翻出别人的提交内容 */
        public boolean filterBySubmitter() {
            return !Boolean.TRUE.equals(isOwner);
        }
    }
}
