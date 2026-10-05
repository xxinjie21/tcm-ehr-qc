package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * NLP 批量解析提交请求。
 */
@Data
public class NlpBatchDTO {

    /** 筛选范围：科室 / 时间区间 / 证候 / 分级（与病历查询同一套） */
    private FiltersDTO filters;

    /**
     * 处理条数上限；空或 0 表示不限（按筛选范围内全部）。
     *
     * <p>上限 40000 = 目标数据集规模（实测 35355 条 × 1.13 KB/条）。此前无上限，
     * 一次误传 {@code limit=1000000} 就会建出一个跑几小时的任务；前端虽有 max，
     * 直调 API 绕得过。0 与负数按「不限」处理，故用 {@code @PositiveOrZero} 而非 {@code @Min(1)}。</p>
     */
    @PositiveOrZero(message = "处理条数上限不能为负数")
    @Max(value = 40000, message = "处理条数上限不能超过 40000")
    private Integer limit;

    /**
     * 幂等键（批次 5）：可空。
     *
     * <p>客户端在**一次用户动作**里生成一个随机值，失败重试沿用同一个；用户再次主动点击则应换新值
     * —— 那是新的意图，不该被去重。</p>
     *
     * <p>为什么不由服务端「按筛选条件哈希」生成：条件和上次相同、但用户就是想再跑一遍，是合法的新意图；
     * 只有客户端知道这两次请求是不是同一个动作。这也是 Stripe 等平台要求调用方传幂等键的原因。</p>
     */
    private String requestKey;
}
