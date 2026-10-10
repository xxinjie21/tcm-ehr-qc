package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.metrics.Metrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运行期指标读取（Redis 9.1 / P1-14）。
 *
 * <p>只读进程内累加器，不触发任何计算；进程重启即归零。口径见 {@link Metrics}。</p>
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    /**
     * 全部指标的只读快照。
     *
     * <p>【权限：仅管理员】含缓存命中率、AI 任务耗时分布、LLM 客户端缓存命中率等运行期信息，
     * 对普通用户无意义且有信息泄露面，故不对外开放。</p>
     *
     * @return {counters, timers}
     */
    @GetMapping
    @RequireRole(roles = {"管理员"})
    public Result<Map<String, Object>> snapshot() {
        return Result.ok(Metrics.snapshot());
    }
}
