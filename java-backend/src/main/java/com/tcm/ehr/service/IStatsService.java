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
 * 统计服务：首页指标卡 + 按type统计（疾病/证候/症状/方剂中药频次）
 * + 看板扩展（批C·4.2：趋势/科室合格率/评分分布/词典规模）。
 */
public interface IStatsService extends IService<Record> {

    /** 首页四个指标卡（病历总数/合格率/待复核/无效） */
    OverviewVO overview();

    /** 按 type 统计（recordIds 圈定范围，空则按 filters 或全量） */
    StatsVO stats(StatsDTO dto);

    /** 科室动态选项（批B·4.1 U11） */
    List<String> departments();

    /** 看板一次拉取：指标卡 + 4 类统计（批C·4.2 U6，filters 与范围条联动） */
    StatsAllVO all(FiltersDTO filters);

    /** 看板扩展统计：趋势 / 科室合格率 / 评分分布 / 词典规模（批C·4.2，并入 StatsVO 扩展字段） */
    StatsVO extra(FiltersDTO filters);
}
