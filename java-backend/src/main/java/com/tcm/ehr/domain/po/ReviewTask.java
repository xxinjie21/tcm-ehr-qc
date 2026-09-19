package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("review_tasks")
public class ReviewTask {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    private String recordId;
    /** 状态：pending/completed */
    private String status;
    /** 问题类型（缺失字段/逻辑冲突/评分不达标） */
    private String issueType;
    /** 当前评分 */
    private Integer score;
    private LocalDateTime createTime;
    /** 复核截止时间（创建时间+7个工作日） */
    private LocalDateTime deadlineTime;
    /** 复核人用户名 */
    private String reviewedBy;
    /** 复核完成时间 */
    private LocalDateTime completedTime;
    /** 作废标记：病历重新评分后不再是待复核则置 1（查询/统计统一过滤 is_obsolete=0） */
    private Integer isObsolete;
}
