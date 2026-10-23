package com.tcm.ehr.domain.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量任务（NLP 批解析 / 质控批量重算）的公共状态常量与进度字段。
 *
 * 两张任务表结构同构（nlp_task / qc_task）：状态机与进度字段完全一致，
 * 只有质控多出的分级计数与角色快照留在各自子类。抽公共基类是为了让「状态口径」只有一处 ——
 * 否则给状态机加一档时，两个类很容易改漏一个。
 */
@Data
public abstract class AbstractBatchTask {

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
    /** 当前处理的病历标识（登记号 / ID） */
    private String currentLabel;
    /** 筛选范围 JSON（FiltersDTO） */
    private String filtersJson;
    private String createdBy;
    /**
     * 提交时所属组的快照，供后台 worker 重建 RecordFilter。
     *
     * 不是冗余：worker 跑在后台线程，读不到 RequestContextHolder，
     * 必须用快照值；否则数据域过滤会退化成「不过滤 = 全库」。
     */
    private String orgId;
    /**
     * 幂等键（批次 5）：客户端在一次提交动作里生成，网络重试 / 重复点击沿用同一个值。
     *
     * 与 {@code (org_id, request_key)} 唯一索引配对使用：撞键时服务层查回既有任务原样返回，
     * 不新建也不重跑 —— 于是「手抖点了两下」与「请求被网关重放」都只会留下一条任务。
     */
    private String requestKey;
    /**
     * 是否已请求取消（1=是）。批次 4 加的列，批次 9 真正用起来。
     *
     * 取消位落库而不是只放内存：多实例下「A 点取消、B 在跑」时，
     * 内存 Set B 看不见，必须以这一列为准。
     */
    private Integer cancelRequested;
    /** 失败清单 JSON 数组（仅存前 500 条） */
    private String failureList;
    private Boolean failureTruncated;
    /**
     * 完整失败记录 ID 集合（JSON 数组），任务收尾时写一次。
     *
     * 与 {@link #failureList} 的分工：后者只存<b>前 500 条</b>失败明细（供界面展示），
     * 本列存<b>全部</b>失败记录 ID —— 「仅重试失败」要按完整集合重跑，不能被 500 条截断漏掉。
     */
    private String failedIds;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
