package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.DeductionStatsVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.QcRulesVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;

/**
 * 质控服务（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算；批Q 规则可配置。
 */
public interface IQcService extends IService<Record> {

    QcCheckVO check(QcCheckDTO dto);

    LogicCheckVO checkLogic(LogicCheckDTO dto);

    ScoreResultVO score(QcScoreDTO dto);

    QcBatchResultVO scoreBatch(QcBatchDTO dto);

    /** 读取当前质控规则（+ 加载告警） */
    QcRulesVO rules();

    /** 保存规则（立即生效，落盘 data/qc-rules.json） */
    QcRulesVO updateRules(QcRuleSet rules);

    /** 恢复默认规则 */
    QcRulesVO resetRules();

    /** 范围扣分维度聚合 */
    DeductionStatsVO deductionStats(FiltersDTO filters);
}
