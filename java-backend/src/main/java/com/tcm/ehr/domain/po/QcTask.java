package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量质控重算任务。对应 {@code qc_task} 表。
 *
 * <p>§七 L5：批量重算由同步改异步 —— 原实现一个请求线程从头跑到尾，40000 条会把
 * 前端请求挂到超时。进度与失败清单落库，故提交后可以关页面、靠轮询看进度；
 * 重启时把未完成任务标为 {@code INTERRUPTED}（不可续跑），与 {@code nlp_task} 同口径。
 * 公共字段见 {@link AbstractBatchTask}。</p>
 *
 * <p>⚠️ {@link #role} 是<b>提交时的角色快照</b>，不是冗余：worker 跑在后台线程里，
 * {@code RequestUtils.currentRole()} 读的是线程绑定的 {@code RequestContextHolder}，
 * 取到的是字符串 {@code "unknown"}（不是 null）。若拿它去 {@code RecordFilter.build}，
 * {@code domainGrade} 返回 null，数据域过滤会退化成「不过滤 = 全库」——
 * 表面看任务跑通了，实际把用户看不见的病历也重算了。
 * 正确做法：提交线程捕获角色写进本列，worker 用捕获值重建过滤器。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qc_task")
public class QcTask extends AbstractBatchTask {

    /** 合格数 */
    private Integer qualified;
    /** 待复核数 */
    private Integer pendingReview;
    /** 无效数 */
    private Integer invalid;
    /** 提交时的角色快照，仅用于审计日志回填（见类注释） */
    private String role;
}
