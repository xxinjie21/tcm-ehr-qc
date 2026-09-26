package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;

import java.util.List;

/**
 * 统计服务：首页指标卡、按类型统计、科室选项、看板一次拉取与扩展统计。
 *
 * <p>所有聚合都受数据域约束：管理员全库，审核员仅待复核域。</p>
 */
public interface IStatsService extends IService<Record> {

    /**
     * 首页四个指标卡。
     *
     * @return total/qualified/pendingReview/invalid=各分级条数
     */
    OverviewVO overview();

    /**
     * 按类型统计。
     *
     * @param dto type=统计类型；recordIds=限定病历集合，为空表示全范围
     * @return 词频或分布结果
     */
    StatsVO stats(StatsDTO dto);

    /**
     * 科室下拉选项。
     *
     * @return 当前数据域内的科室去重值
     */
    List<String> departments();

    /**
     * 看板一次拉取：指标卡 + 四类统计。
     *
     * @param filters 范围条件
     * @return 指标卡与四类统计
     */
    StatsAllVO all(FiltersDTO filters);

    /**
     * 看板扩展统计：趋势、科室合格率、评分分布、词典规模。
     *
     * @param filters 范围条件
     * @return 四类扩展统计
     */
    StatsVO extra(FiltersDTO filters);
}
