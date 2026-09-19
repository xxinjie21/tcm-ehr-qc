package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsVO;

/**
 * 统计服务：首页指标卡 + 按type统计（疾病/证候/症状/方剂中药频次）
 */
public interface IStatsService extends IService<Record> {

    /** 首页四个指标卡（病历总数/合格率/待复核/无效） */
    OverviewVO overview();

    /** 按 type 统计（recordIds 圈定范围，空则按 filters 或全量） */
    StatsVO stats(StatsDTO dto);
}
