package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组织级质控规则（对应 {@code qc_rules} 表，{@code org_id} 为主键）。
 *
 * <p>规则改成「每个组织一份」：该组织无记录时回退内置默认（{@code QcRuleSet.defaults()}），
 * 不落库 —— 避免给所有组织都写一份默认行。</p>
 */
@Data
@TableName("qc_rules")
public class QcRule {

    /** 组织 ID；无记录表示用内置默认 */
    @TableId(type = IdType.INPUT)
    private String orgId;

    /** 规则 JSON（QcRuleSet 序列化结果） */
    private String rulesJson;

    private LocalDateTime updateTime;
}
