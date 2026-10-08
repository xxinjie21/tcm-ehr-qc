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

    /**
     * 为一批待规范术语给出补词建议（termsuggest）。
     *
     * <p>先按字面相似度从现有词表召回候选，再让 LLM 在候选约束下判断
     * 「挂别名 / 新建标准词 / 忽略」。<b>只出候选，不落库</b> ——
     * 录入必须由人工确认，归一链路不消费本结果。</p>
     *
     * @param dto terms=待规范原文（必填）；termType=术语类型，留空按 symptom
     * @return 建议列表；LLM 不可用时降级为「仅召回候选」，恒不返回 {@code null}
     */
    AiReplyVO suggestTerms(AiQueryDTO dto);
}
