package com.tcm.ehr.controller;

import com.tcm.ehr.common.Result;
import com.tcm.ehr.dto.StatsDTO;
import com.tcm.ehr.service.StatsService;
import com.tcm.ehr.vo.OverviewVO;
import com.tcm.ehr.vo.StatsVO;
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

    private final StatsService statsService;

    /** 首页四个指标卡一次获取 */
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.ok(statsService.overview());
    }

    /** 按type统计（recordIds圈定范围，空=全量） */
    @PostMapping
    public Result<StatsVO> stats(@RequestBody StatsDTO dto) {
        try {
            return Result.ok(statsService.stats(dto));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
