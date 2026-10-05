package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;

import java.io.IOException;
import java.util.List;

/**
 * ES 术语索引服务：构建 term_{type} 索引并检索候选。
 *
 * <p>索引与检索放在同一处，字段名与映射只有一份权威。</p>
 *
 * <p><b>组织级</b>：文档带 {@code org_id}（空串 {@code ""} = 基础层）。同一个
 * {@code (type, org_id)} 的词条写到同一索引内，用 {@code org_id} 过滤来避免跨组织串词。
 * 这是「ES 是归一唯一权威」的组织隔离闭环。</p>
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
     * 索引的映射是否与当前代码兼容。
     *
     * <p>判据：{@code org_id} 必须是 {@code keyword}。索引不存在、或 org_id 被动态映射成
     * {@code text}（旧版本升级会这样）都返回 {@code false}。</p>
     *
     * <p><b>为什么启动对账要用它</b>：{@code dictionary_versions} 只记「内容版本对不对得上」，
     * 记不了「索引结构对不对」。旧版本升级后内容版本可能已标为同步，但索引结构是坏的 ——
     * 只信版本号会跳过重建，坏索引就永远修不好（实测：全库归一「未收录」）。</p>
     *
     * @param type 术语类型
     * @return 兼容返回 true；索引不存在或不兼容返回 false
     * @throws IOException ES 请求失败
     */
    boolean schemaCompatible(String type) throws IOException;

    /**
     * 全量重建某组织（或基础层）的词条索引。
     *
     * <p>重建期间有检索空窗，调用方需容忍 {@code index_not_found}；归一接口据此回 503。
     * 启动时先比对 {@link #indexedVersion(String, String)}，一致就不重建。</p>
     *
     * @param type    术语类型
     * @param orgId   组织号；{@code ""} = 基础层
     * @param entries 词条列表
     * @param version 词典内容版本，写进索引 _meta 供下次启动比对
     */
    void rebuild(String type, String orgId, List<TermEntry> entries, String version) throws IOException;

    /** 向后兼容：默认重建基础层（仅用于尚未改完的调用点临时兜底，逐步淘汰） */
    default void rebuild(String type, List<TermEntry> entries, String version) throws IOException {
        rebuild(type, "", entries, version);
    }

    /**
     * 读取索引里记录的词典版本。
     *
     * @param type  术语类型
     * @param orgId 组织号；{@code ""} = 基础层
     * @return 版本号；索引不存在或为旧索引（无该标记）返回 {@code null}
     */
    String indexedVersion(String type, String orgId) throws IOException;

    /** 向后兼容：默认读基础层 */
    default String indexedVersion(String type) throws IOException {
        return indexedVersion(type, "");
    }

    /**
     * 召回候选词条（宽松，宁多勿漏）。
     *
     * <p>ES 是归一的唯一权威：未召回即视为未命中。索引不可用时抛 IOException，
     * 由上层包成 503 返回；<b>不要在这里吞异常或返回空列表</b>，那会把"索引挂了"
     * 伪装成"词典没这个词"。命中与否由 EsTermNormalizer 按三级规则与 Dice 阈值判定。</p>
     *
     * @param type          术语类型
     * @param orgId         当前组织号；会同时命中 {@code org_id = ''}（基础层）
     * @param input         输入词
     * @param maxCandidates 召回上限
     * @return 候选词条（按 ES 相关度排序）；无命中返回空列表
     */
    List<TermEntry> search(String type, String orgId, String input, int maxCandidates) throws IOException;

    /**
     * 批量召回（批次 12 · 12b）：一次 ES 往返查多个术语。
     *
     * <p>用 ES 的 {@code _msearch} 而不是手写 {@code bool.should}：msearch 的每个子查询与
     * {@link #search} 逐字相同、响应按提交顺序返回，因此<b>结果与逐个查逐条一致</b>是结构上成立的，
     * 而不是靠对账；手写 should 则无法把命中归属回输入。</p>
     *
     * <p>入参里的重复项与空白项由实现负责处理（重复只查一次、空白直接给空结果），
     * 返回的 Map 对每个去重后的输入都给一个键（无命中给空列表，不返回 null）。</p>
     *
     * @param type          术语类型
     * @param orgId         当前组织；会同查 {@code org_id = ''}（基础层）
     * @param inputs        待查术语（可含重复与空白）
     * @param maxCandidates 每个术语的召回上限
     * @return 输入 → 候选词条（按 ES 相关度排序）；无命中为空列表
     */
    java.util.Map<String, List<TermEntry>> searchBatch(String type, String orgId, List<String> inputs,
                                                       int maxCandidates) throws IOException;

    /** 累计已发出的 ES 检索请求数（批次 12 · 12b 的「请求数下降」打点；重启后归零） */
    long searchRequestCount();

    /** 向后兼容：默认只查基础层（逐步淘汰） */
    default List<TermEntry> search(String type, String input, int maxCandidates) throws IOException {
        return search(type, "", input, maxCandidates);
    }
}
