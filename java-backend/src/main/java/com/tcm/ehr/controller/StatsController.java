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
 * 统计接口：首页指标卡、科室选项、看板一次拉取与扩展统计、按类型统计。
 *
 * <p>【权限：登录即可】但受数据域约束：管理员看全库，审核员仅待复核域。所有聚合都必须带数据域，
 * 漏一处审核员就能读到全库的分级分布与词频。</p>
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final IStatsService statsService;

    /**
     * 首页四个指标卡。
     *
     * <p>【权限：登录即可 + 数据域】</p>
     *
     * @return total=病历总数；qualified=合格数；pendingReview=待复核数；invalid=无效数
     */
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.ok(statsService.overview());
    }

    /**
     * 科室下拉选项。
     *
     * <p>【权限：登录即可】取当前数据域内的科室去重值。</p>
     *
     * @return 科室名称列表
     */
    @GetMapping("/departments")
    public Result<List<String>> departments() {
        return Result.ok(statsService.departments());
    }

    /**
     * 看板一次拉取：指标卡 + 四类统计。
     *
     * <p>【权限：登录即可 + 数据域】</p>
     *
     * @param department 科室，空表示不限
     * @param start      接诊时间下界（yyyy-MM-dd），与 end 同时给才生效
     * @param end        接诊时间上界（yyyy-MM-dd）
     * @param pattern    证候关键字，模糊匹配
     * @param grade      分级（合格/待复核/无效）
     * @return 指标卡与四类统计的聚合结果
     */
    @GetMapping("/all")
    public Result<StatsAllVO> all(@RequestParam(required = false) String department,
                                  @RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  @RequestParam(required = false) String pattern,
                                  @RequestParam(required = false) String grade) {
        return Result.ok(statsService.all(filters(department, start, end, pattern, grade)));
    }

    /**
     * 看板扩展统计：趋势、科室合格率、评分分布、词典规模。
     *
     * <p>【权限：登录即可 + 数据域】</p>
     *
     * @param department 科室，空表示不限
     * @param start      接诊时间下界（yyyy-MM-dd），与 end 同时给才生效
     * @param end        接诊时间上界（yyyy-MM-dd）
     * @param pattern    证候关键字，模糊匹配
     * @param grade      分级
     * @return 趋势/科室/分布/词典规模的聚合结果
     */
    @GetMapping("/extra")
    public Result<StatsVO> extra(@RequestParam(required = false) String department,
                                 @RequestParam(required = false) String start,
                                 @RequestParam(required = false) String end,
                                 @RequestParam(required = false) String pattern,
                                 @RequestParam(required = false) String grade) {
        return Result.ok(statsService.extra(filters(department, start, end, pattern, grade)));
    }

    /**
     * 按类型统计词频或分布。
     *
     * <p>【权限：登录即可 + 数据域】recordIds 为空表示统计全范围；type 非法由
     * GlobalExceptionHandler 统一返回 400。</p>
     *
     * @param dto type=统计类型；recordIds=限定病历集合，可空
     * @return 该类型的词频或分布结果
     */
    @PostMapping
    public Result<StatsVO> stats(@RequestBody StatsDTO dto) {
        return Result.ok(statsService.stats(dto));
    }

    /** GET 查询参数 → 统一的范围过滤对象（时间需上下界同时存在） */
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
