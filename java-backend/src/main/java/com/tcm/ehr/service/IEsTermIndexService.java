package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.util.List;

/**
 * ES术语索引服务：term_{type} 索引的构建与检索。
 * standard_term(keyword精确 + text ik_max_word子字段) + aliases(text ik_max_word数组，keyword子字段) + source(keyword)
 *
 * <p>检索与索引同处一个服务，是为了让字段名/映射只有一处权威——分开放极易漂移。</p>
 */
public interface IEsTermIndexService {

    /** 索引名：term_{type} */
    String indexName(String type);

    /** 索引是否存在 */
    boolean exists(String type) throws IOException;

    /** 全量重建：删除旧索引 -> 建索引 -> bulk灌入 */
    void rebuild(String type, List<TermEntry> entries) throws IOException;

    /**
     * 候选召回（宽松，宁可多召回）：精确(标准词/别名) + 分词命中(标准词/别名) 的 OR 组合。
     *
     * <p>只负责「把可能相关的词条捞出来」，<b>判定不在这里做</b>——命中与否由
     * {@link com.tcm.ehr.common.utils.EsTermNormalizer} 按三级规则 + Dice 阈值决定。</p>
     *
     * <p><b>召回是近似的，而本索引现在是归一的唯一权威</b>（2026-09-23 起不再有内存兜底）：
     * 未召回即视为未命中。ES 不可用时本方法抛 {@link java.io.IOException}，
     * 由 {@link com.tcm.ehr.common.utils.EsTermNormalizer} 包装成
     * {@link com.tcm.ehr.common.exception.TermIndexUnavailableException} 返回 503，
     * <b>不要在这里吞掉异常或返回空列表</b> —— 那会把「索引挂了」伪装成「词典没这个词」。</p>
     *
     * @param type          术语类型
     * @param input         输入词
     * @param maxCandidates 召回上限
     * @return 候选词条（按 ES 相关度排序）；索引不存在或无命中返回空列表
     */
    List<TermEntry> search(String type, String input, int maxCandidates) throws IOException;
}
