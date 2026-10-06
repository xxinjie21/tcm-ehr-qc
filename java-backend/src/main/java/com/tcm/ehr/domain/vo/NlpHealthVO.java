package com.tcm.ehr.domain.vo;

import lombok.Data;

/**
 * NLP 抽取服务的健康探测结果（GET /api/nlp/health）。
 *
 * <p>在这之前，页面只有「先点执行抽取、拿回空结果」才知道服务没起来 —— 而空结果
 * 和「原文确实没写要素」长得一模一样，用户没法判断该不该重试、该不该找人开服务。
 * 这里把「能不能抽、缺的是哪一半」提前问出来，解析页可以在用户动手前给出结论，
 * 结构化数据卡片也能据此判断「已抽取但 9 类全空」是不是一次降级抽取留下的。</p>
 *
 * <p>字段取值与 {@link NlpExtractVO#getUnavailableReason()} 同一套常量，
 * 前端不需要为探测结果再学一套词汇。</p>
 */
@Data
public class NlpHealthVO {

    /** 抽取总开关（nlp.enabled）；false 时后面的字段均无意义 */
    private boolean enabled;

    /** Python 抽取服务是否可达（已启用且 /health 返回 200） */
    private boolean reachable;

    /** 模型是否加载成功；服务不可达时为 false */
    private boolean modelAvailable;

    /**
     * 降级原因：{@link NlpExtractVO#REASON_DISABLED} / {@link NlpExtractVO#REASON_UNREACHABLE}
     * / {@link NlpExtractVO#REASON_MODEL_MISSING}；一切正常为 {@code null}。
     */
    private String unavailableReason;
}
