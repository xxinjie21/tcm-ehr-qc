package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.util.List;

/**
 * ES 术语索引服务：构建 term_{type} 索引并检索候选。
 *
 * <p>索引与检索放在同一处，字段名与映射只有一份权威。</p>
 */
public interface IEsTermIndexService {

    /**
     * 取索引名。
     *
     * @param type 术语类型
     * @return term_{type}
     */
    String indexName(String type);

    /**
     * 索引是否存在。
     *
     * @param type 术语类型
     * @return 存在返回 true
     */
    boolean exists(String type) throws IOException;

    /**
     * 全量重建索引。
     *
     * <p>重建期间有检索空窗，调用方需容忍 {@code index_not_found}；归一接口据此回 503。
     * 启动时先比对 {@link #indexedVersion}，一致就不重建。</p>
     *
     * @param type    术语类型
     * @param entries 词条列表
     * @param version 词典版本，写进索引 _meta 供下次启动比对
     */
    void rebuild(String type, List<TermEntry> entries, String version) throws IOException;

    /**
     * 读取索引里记录的词典版本。
     *
     * @param type 术语类型
     * @return 版本号；索引不存在或为旧索引（无该标记）返回 {@code null}
     */
    String indexedVersion(String type) throws IOException;

    /**
     * 召回候选词条（宽松，宁多勿漏）。
     *
     * <p>ES 是归一的唯一权威：未召回即视为未命中。索引不可用时抛 IOException，
     * 由上层包成 503 返回；<b>不要在这里吞异常或返回空列表</b>，那会把"索引挂了"
     * 伪装成"词典没这个词"。命中与否由 EsTermNormalizer 按三级规则与 Dice 阈值判定。</p>
     *
     * @param type          术语类型
     * @param input         输入词
     * @param maxCandidates 召回上限
     * @return 候选词条（按 ES 相关度排序）；无命中返回空列表
     */
    List<TermEntry> search(String type, String input, int maxCandidates) throws IOException;
}
