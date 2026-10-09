package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 提案术语快照（{@code dict_proposal_term}）。
 *
 * <p>存的是<b>提交时的完整目标词典</b>，不是「增量 diff」。这样审核时组长直接在快照上
 * 增删改即可，且合并天然是整快照替换（能支持删除），不需要再算一次差异。</p>
 *
 * <p>被驳回且过期的提案，其快照会被惰性清理；提案主记录仍在。</p>
 */
@Data
@TableName("dict_proposal_term")
public class DictProposalTerm {

    @TableId(type = IdType.INPUT)
    private String id;

    private String proposalId;

    /** 标准词；与基线比对新增/修改/删除的 key */
    private String standardTerm;

    /** 别名数组（JSON 字符串） */
    private String aliases;
}
