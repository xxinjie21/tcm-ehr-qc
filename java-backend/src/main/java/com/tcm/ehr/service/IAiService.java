package com.tcm.ehr.service;

import com.tcm.ehr.domain.dto.AiQueryDTO;
import com.tcm.ehr.domain.vo.AiReplyVO;

/**
 * AI 消费端：质控解读、业务问答、复核预检。
 *
 * <p>规则先出结论，LLM 只做叙述增强；LLM 不可用时降级为规则输出，不阻塞主流程。</p>
 */
public interface IAiService {

    /**
     * 生成质控解读。
     *
     * @param dto recordId=病历ID；query=关注点，可空
     * @return 解读结果；病历不存在返回 {@code null}，由 Controller 转 404
     */
    AiReplyVO interpret(AiQueryDTO dto);

    /**
     * 业务问答。
     *
     * @param dto question=问题；recordId=可选，命中"这份病历…"类问题时作上下文
     * @return 回答结果，含来源标记
     */
    AiReplyVO chat(AiQueryDTO dto);

    /**
     * 生成复核预检意见。
     *
     * @param dto recordId=病历ID
     * @return 预检结果；病历不存在返回 {@code null}
     */
    AiReplyVO review(AiQueryDTO dto);
}
