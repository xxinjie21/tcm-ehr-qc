package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.vo.AiReplyVO;

/**
 * AI 消费端服务（批C · 3.1 解读卡 / 3.2 助手浮窗）。
 *
 * <p>判定地基是规则：解读的完整性/缺项/归一命中结论、助手的降级规则问答均由规则产出；
 * LLM 仅负责把结论叙述成人话，不可用时降级且规则结论保留。</p>
 */
public interface IAiService {

    /**
     * AI 质控解读：规则出结论 + LLM 叙述与要点摘要。
     *
     * @return 解读结果；病历不存在返回 {@code null}（由控制器回 404）
     */
    AiReplyVO interpret(AiQueryDTO dto);

    /** AI 助手问答：业务问题走 LLM+规则检索，技术实现问题兜底拒答，LLM 不可用降级规则问答 */
    AiReplyVO chat(AiQueryDTO dto);
}
