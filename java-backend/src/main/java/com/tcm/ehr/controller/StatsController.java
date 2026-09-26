package com.tcm.ehr.controller;

import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.service.IStatsService;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计：首页指标卡 + 按type统计（症状频次/证型分布/方剂中药频次）
 * + 看板扩展（批C·4.2：stats/all 一次拉取 / stats/extra 趋势·科室·评分分布）。
 *
 * <p>【权限：登录即可】，但读取<b>受数据域约束</b>——审核员仅待复核域，管理员全库
 * （openapi 全局声明）。聚合 SQL 与列表查询都必须带数据域：漏一处，审核员就能读到
 * 全库的分级分布与证型 / 方剂词频。</p>
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

    /** 科室动态选项（批B·4.1 U11）；【权限：登录即可】 */
    @GetMapping("/departments")
    public Result<List<String>> departments() {
        return Result.ok(statsService.departments());
    }

    /** 看板一次拉取：指标卡 + 4 类统计（批C·4.2 U6）；【权限：登录即可】 */
    @GetMapping("/all")
    public Result<StatsAllVO> all(@RequestParam(required = false) String department,
                                  @RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  @RequestParam(required = false) String pattern,
                                  @RequestParam(required = false) String grade) {
        return Result.ok(statsService.all(filters(department, start, end, pattern, grade)));
    }

    /** 看板扩展统计：趋势 / 科室合格率 / 评分分布 / 词典规模（批C·4.2）；【权限：登录即可】 */
    @GetMapping("/extra")
    public Result<StatsVO> extra(@RequestParam(required = false) String department,
                                 @RequestParam(required = false) String start,
                                 @RequestParam(required = false) String end,
                                 @RequestParam(required = false) String pattern,
                                 @RequestParam(required = false) String grade) {
        return Result.ok(statsService.extra(filters(department, start, end, pattern, grade)));
    }

    /** 按type统计（recordIds圈定范围，空=全量）；【权限：登录即可 + 数据域（审核员恒为待复核域）】；type非法由 GlobalExceptionHandler 统一返回 400 */
    @PostMapping
    public Result<StatsVO> stats(@RequestBody StatsDTO dto) {
        return Result.ok(statsService.stats(dto));
    }

    /** GET query 参数 → FiltersDTO */
    private FiltersDTO filters(String department, String start, String end, String pattern, String grade) {
        FiltersDTO f = new FiltersDTO();
        f.setDepartment(department);
        f.setPattern(pattern);
        f.setGrade(grade);
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            List<String> range = new ArrayList<>(2);
            range.add(start);
            range.add(end);
            f.setDateRange(range);
        }
        return f;
    }
}
