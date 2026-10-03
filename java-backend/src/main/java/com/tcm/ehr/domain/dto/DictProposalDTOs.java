package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 词典基线更新提案的入参（批次 17）。
 *
 * <p>提案携带的是<b>完整目标词典</b>而非增量 diff：这样审核时组长直接在快照上增删改即可，
 * 合并天然是整快照替换（因此能支持删除），不必在合并时再算一次差异。</p>
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
}
