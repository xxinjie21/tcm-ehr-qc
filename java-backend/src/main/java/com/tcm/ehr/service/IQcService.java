package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
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

import java.util.Set;

/**
 * 质控服务：事前检查、逻辑一致性、单条评分，以及评分规则的维护与扣分聚合。
 *
 * <p>§七 L5：批量重算已从本接口移除（改为异步任务，见 {@code IQcBatchService}）。
 * 本接口保留 {@code processOne} 所需的单条处理能力，供 worker 复用。</p>
 *
 * <p>评分口径由 {@link QcRuleSet} 决定，判定不经 LLM。</p>
 */
public interface IQcService extends IService<Record> {

    /**
     * 事前检查：核心要素缺失与格式校验。
     *
     * @param dto recordId=病历ID；structuredData=可选，缺省读库
     * @return missingFields=缺失要素；formatErrors=格式问题
     */
    QcCheckVO check(QcCheckDTO dto);

    /**
     * 逻辑一致性检查。
     *
     * @param dto patternList/treatmentList/formulaList 待判定要素
     * @return conflicts=冲突描述；consistent=是否无冲突
     */
    LogicCheckVO checkLogic(LogicCheckDTO dto);

    /**
     * 单条评分（不落库）。
     *
     * @param dto recordId=病历ID；structuredData=可选，缺省读库
     * @return score=得分；grade=分级；deductions=扣分明细
     */
    ScoreResultVO score(QcScoreDTO dto);

    /**
     * 单条处理：评分 + 回写 + upsertReviewTask + 分级计数，供批量 worker 复用。
     *
     * @param r        已载入的病历
     * @param result   批量结果累计器
     * @param seenHash 已处理内容指纹集合（防同内容重复计分）
     * @param rules    本次批量使用的规则集
     * @throws Exception 单条处理的异常由调用方决定是否计入失败清单
     */
    void processOne(Record r, QcBatchResultVO result, Set<String> seenHash, QcRuleSet rules) throws Exception;

    /**
     * 读取当前生效规则。
     *
     * @return rules=规则集；descriptions=自然语言描述；warnings=加载告警
     */
    QcRulesVO rules();

    /**
     * 保存规则并立即生效。
     *
     * @param rules 规则集，落盘到 qc-rules.json
     * @return 保存后的规则集
     */
    QcRulesVO updateRules(QcRuleSet rules);

    /**
     * 恢复默认规则。
     *
     * @return 恢复后的规则集
     */
    QcRulesVO resetRules();

    /**
     * 汇总范围内扣分分布。
     *
     * @param filters 范围条件
     * @return 扫描条数、维度与明细扣分、分级分布
     */
    DeductionStatsVO deductionStats(FiltersDTO filters);
}
