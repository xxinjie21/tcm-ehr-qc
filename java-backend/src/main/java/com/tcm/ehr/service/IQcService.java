package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;

/**
 * 质控服务（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算。
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
}
