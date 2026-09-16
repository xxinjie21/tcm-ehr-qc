package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.service.IStatsService;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计：首页指标卡 + 按type统计（症状频次/证型分布/方剂中药频次）
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final IStatsService statsService;

    /** 首页四个指标卡一次获取 */
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.ok(statsService.overview());
    }

    /** 按type统计（recordIds圈定范围，空=全量）；type非法由 GlobalExceptionHandler 统一返回 400 */
    @PostMapping
    public Result<StatsVO> stats(@RequestBody StatsDTO dto) {
        return Result.ok(statsService.stats(dto));
    }
}
