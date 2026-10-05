package com.tcm.ehr.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * NLP 批量解析任务。对应 {@code nlp_task} 表。
 *
 * <p>进度与失败清单落库，故服务重启后仍可查询；重启时把未完成任务标为 {@code INTERRUPTED}（K-c）。</p>
 */
@Data
@TableName("nlp_task")
public class NlpTask {

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
     * 提交时所属组的快照。
     * <p>不是冗余：worker 跑在后台线程，读不到 {@code RequestContextHolder}，必须用快照值重建
     * {@code RecordFilter}；否则数据域过滤会退化成「不过滤 = 全库」。</p>
     */
    private String orgId;
    /**
     * 幂等键（批次 5）：客户端在一次提交动作里生成，网络重试 / 重复点击沿用同一个值。
     *
     * <p>与 {@code (org_id, request_key)} 唯一索引配对使用：撞键时服务层查回既有任务原样返回，
     * 不新建也不重跑 —— 于是「手抖点了两下」与「请求被网关重放」都只会留下一条任务。</p>
     */
    private String requestKey;

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
