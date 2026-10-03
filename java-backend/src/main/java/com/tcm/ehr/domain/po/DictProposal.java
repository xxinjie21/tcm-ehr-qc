package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 词典基线更新提案（{@code dict_proposal}）。
 *
 * <p>成员不能直接改小组基线，只能提交一份「完整目标词典」提案；组长审核通过后
 * 才合并进 {@code dictionary_terms} 并生成归档版本。</p>
 *
 * <p><b>主记录永久保留</b>（含被驳回的），只有 {@code dict_proposal_term} 快照会在
 * {@code purge_after} 到期后被惰性清理 —— 提案本身是审计凭据。</p>
 */
@Data
@TableName("dict_proposal")
public class DictProposal {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属组织；{@code ""} = 基础层（与组织层归档限额独立计数） */
    private String orgId;

    /** 术语类型；一次提案只针对一种类型 */
    private String type;

    /** 提交人 */
    private String submitUserId;

    /** {@code pending} / {@code approved} / {@code rejected} */
    private String status;

    /** 审核人（组长） */
    private String auditUserId;

    /** 审核意见 / 拒绝理由 */
    private String auditComment;

    private LocalDateTime createTime;

    private LocalDateTime auditTime;

    /** 仅 {@code rejected} 填：+7 天后惰性清理快照；主记录不受影响 */
    private LocalDateTime purgeAfter;

    // ---- 状态常量（避免散落字符串） ----

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
