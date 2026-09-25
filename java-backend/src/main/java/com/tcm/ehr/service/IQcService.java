package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.GraphVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;

/**
 * 质控服务（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算；批D·3.3 增图谱聚合。
 */
public interface IQcService extends IService<Record> {

    /** 事前质控（缺失 / 格式 / 查重） */
    QcCheckVO check(QcCheckDTO dto);

    /** 诊疗逻辑一致性 */
    LogicCheckVO checkLogic(LogicCheckDTO dto);

    /** 单条评分（不落库，返回结果） */
    ScoreResultVO score(QcScoreDTO dto);

    /** 全库/范围内批量重算（写 records + review_tasks） */
    QcBatchResultVO scoreBatch(QcBatchDTO dto);

    /** 质控检验图谱（批D·3.3）：范围内聚合"病历—实体" + LogicChecker 规则/冲突边 */
    GraphVO graph(FiltersDTO filters);

    /** 评分标准（批P）：只读下发当前评分口径与逻辑规则，供前端"标准可视化" */
    com.tcm.ehr.domain.vo.QcRuleSetVO rules();

    /** 范围扣分维度聚合（批P）：范围内各病历扣分明细按维度/明细汇总 */
    com.tcm.ehr.domain.vo.DeductionStatsVO deductionStats(FiltersDTO filters);
}
