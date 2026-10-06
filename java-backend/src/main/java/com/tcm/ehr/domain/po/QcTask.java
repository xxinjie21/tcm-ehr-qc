package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量质控重算任务。对应 {@code qc_task} 表。
 *
 * <p>§七 L5：批量重算由同步改异步 —— 原实现一个请求线程从头跑到尾，40000 条会把
 * 前端请求挂到超时。进度与失败清单落库，故提交后可以关页面、靠轮询看进度；
 * 重启时把未完成任务标为 {@code INTERRUPTED}（不可续跑），与 {@code nlp_task} 同口径。</p>
 *
 * <p>⚠️ {@link #role} 是<b>提交时的角色快照</b>，不是冗余：worker 跑在后台线程里，
 * {@code RequestUtils.currentRole()} 读的是线程绑定的 {@code RequestContextHolder}，
 * 取到的是字符串 {@code "unknown"}（不是 null）。若拿它去 {@code RecordFilter.build}，
 * {@code domainGrade} 返回 null，数据域过滤会退化成「不过滤 = 全库」——
 * 表面看任务跑通了，实际把用户看不见的病历也重算了。
 * 正确做法：提交线程捕获角色写进本列，worker 用捕获值重建过滤器。</p>
 */
@Data
@TableName("qc_task")
public class QcTask {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String FAILED = "FAILED";

    private String id;
    private String status;
    private Integer total;
    private Integer done;
    private Integer success;
    private Integer failed;
    /** 合格数 */
    private Integer qualified;
    /** 待复核数 */
    private Integer pendingReview;
    /** 无效数 */
    private Integer invalid;
    /** 当前处理的病历标识（登记号 / ID） */
    private String currentLabel;
    /** 筛选范围 JSON（FiltersDTO） */
    private String filtersJson;
    /**
     * 幂等键（批次 5）：客户端在一次提交动作里生成，重放沿用同一个值。
     *
     * <p>与 {@code (org_id, request_key)} 唯一索引配对：撞键时服务层查回既有任务原样返回。
     * 与 {@code DistLock}（Redisson 锁）防重互补 —— 锁管「同一瞬间的并发」，本键管「同一次动作的回放」。</p>
     */
    private String requestKey;
    private String createdBy;
    /** 提交时的角色快照，仅用于审计日志回填（觑类注释） */
    private String role;
    /** 
     * 提交时所属组的快照，供 worker 重建 {@code RecordFilter}。
     * <p>与 {@link #role} 不同用途：{@code role} 只回填审计日志，{@code orgId} 才是数据域。
     * 阶段 2 后 {@code RecordFilter.build} 取的是 orgId，所以快照必须是组而不是角色。</p>
     */
    private String orgId;

    /**
     * 是否已请求取消（1=是）。批次 4 加的列，批次 9 真正用起来。
     *
     * <p>取消位落库而不是只放内存：多实例下「A 点取消、B 在跑」时，
     * 内存 Set B 看不见，必须以这一列为准。</p>
     */
    private Integer cancelRequested;
    /** 失败清单 JSON 数组（仅存前 500 条） */
    private String failureList;
    private Boolean failureTruncated;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
