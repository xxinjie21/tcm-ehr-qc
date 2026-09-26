package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetMappingsRequest;
import org.elasticsearch.client.indices.GetMappingsResponse;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES术语索引服务实现：term_{type} 索引的构建与检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsTermIndexServiceImpl implements IEsTermIndexService {

    public static final String INDEX_PREFIX = "term_";

    private final RestHighLevelClient client;
    private final ObjectMapper objectMapper;

    @Override
    /**
     * 索引名：{@code term_}{@code type}。
     *
     * @param type 术语类型
     * @return ES 索引名
     */
    public String indexName(String type) {
        return INDEX_PREFIX + type;
    }

    @Override
    /**
     * 判断该类术语的 ES 索引是否已存在。
     *
     * @param type 术语类型
     * @return 索引存在时为 {@code true}
     * @throws IOException ES 请求失败
     */
    public boolean exists(String type) throws IOException {
        return client.indices().exists(new GetIndexRequest(indexName(type)), RequestOptions.DEFAULT);
    }

    @Override
    /**
     * 读取索引 mapping 的 {@code _meta.version}，即该索引灌入时所依据的词典版本。
     *
     * @param type 术语类型
     * @return 版本号；索引不存在或为无该标记的旧索引时返回 {@code null}
     * @throws IOException ES 请求失败
     */
    public String indexedVersion(String type) throws IOException {
        // 1. 取该索引的 mapping
        GetMappingsResponse response = client.indices()
                .getMapping(new GetMappingsRequest().indices(indexName(type)), RequestOptions.DEFAULT);
        MappingMetadata meta = response.mappings().get(indexName(type));
        // 2. 索引不存在 → 没有版本可言
        if (meta == null) {
            return null;
        }
        // 3. 从 _meta.version 取版本；旧索引没有这个标记，同样给 null（触发重建）
        Object m = meta.getSourceAsMap().get("_meta");
        if (m instanceof Map<?, ?> map && map.get("version") != null) {
            return String.valueOf(map.get("version"));
        }
        return null;
    }

    @Override
    /**
     * 全量重建：删旧索引 -> 建索引（单分片零副本，写入 _meta.version）-> bulk 灌入全部词条。
     *
     * <p>文档 id 取标准术语的 MD5，同一术语重复灌入会覆盖而非新增。删除与创建之间存在
     * 索引短暂不存在的窗口，期间检索会拿到 index_not_found；故启动路径应先用
     * {@link #indexedVersion} 比对版本，一致即跳过重建。</p>
     *
     * @param type 术语类型
     * @param entries 全量词条
     * @param version 本次灌入的词典版本，写入 {@code _meta.version} 供下次启动比对
     * @throws IOException ES 建索引、灌数据等请求失败
     */
    public void rebuild(String type, List<TermEntry> entries, String version) throws IOException {
        String index = indexName(type);
        // 1. 存在就先删（重建是全量替换，不做增量更新）
        if (exists(type)) {
            client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);
        }
        // 2. 建索引：单分片零副本（演示库数据量小，副本只会拖慢灌入）
        CreateIndexRequest create = new CreateIndexRequest(index);
        create.settings(Map.of("number_of_shards", 1, "number_of_replicas", 0));
        // _meta.version 让下次启动能判断「索引是否已经是当前词典的版本」——
        // 一致就跳过重建，重启期间就不会再出现「索引短暂不存在 → 归一 503」的窗口
        create.mapping(Map.of(
                "_meta", Map.of("version", version == null ? "" : version),
                "properties", mappingProperties()));

        client.indices().create(create, RequestOptions.DEFAULT);

        // 3. 攒成一次 bulk 灌入：逐条发请求会让几千个词条花掉几十秒
        BulkRequest bulk = new BulkRequest();
        for (TermEntry e : entries) {
            // 交给 Jackson 生成文档 JSON，避免手写转义在别名含引号/反斜杠时出错
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("standard_term", e.getStandardTerm());
            doc.put("aliases", e.getAliases() == null ? List.of() : e.getAliases());
            doc.put("source", e.getSource() == null ? "" : e.getSource());
            // 文档 id 取术语哈希：同一术语重复灌入是覆盖，不会出现两条
            bulk.add(new IndexRequest(index).id(hashId(e.getStandardTerm()))
                    .source(objectMapper.writeValueAsString(doc), XContentType.JSON));
        }
        // 4. 词条为空时不发 bulk（ES 会报错，而空索引本身是合法状态）
        if (bulk.numberOfActions() > 0) {
            client.bulk(bulk, RequestOptions.DEFAULT);
        }
        log.info("[ES] 索引 {} 重建完成，术语数: {}", index, entries.size());
    }

    /**
     * 映射：标准词与别名都要「精确可命中」，所以各带一个 keyword 子字段。
     *
     * <p>{@code aliases} 用数组而非空格拼接的字符串——数组下 ES 会为每个别名各建一个
     * {@code aliases.keyword} term，别名精确匹配才成立；拼成单串则 keyword 只能匹配整串。</p>
     */
    private Map<String, Object> mappingProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        // 1. 标准词：主字段 keyword（精确命中）+ text 子字段（分词后模糊命中）
        Map<String, Object> standardTerm = new LinkedHashMap<>();
        standardTerm.put("type", "keyword");
        standardTerm.put("fields", Map.of("text", Map.of("type", "text", "analyzer", "ik_max_word")));
        properties.put("standard_term", standardTerm);

        // 2. 别名：主字段 text + keyword 子字段（见上方 Javadoc：数组才能逐个别名精确匹配）
        Map<String, Object> aliases = new LinkedHashMap<>();
        aliases.put("type", "text");
        aliases.put("analyzer", "ik_max_word");
        aliases.put("fields", Map.of("keyword", Map.of("type", "keyword")));
        properties.put("aliases", aliases);

        // 3. 来源只做展示，不检索
        properties.put("source", Map.of("type", "keyword"));
        return properties;
    }

    @Override
    /**
     * 候选召回：以宽松的 OR 查询取回可能相关的词条，只求不漏、不求准。
     *
     * <p>命中与否不在这里判定，交由 {@code EsTermNormalizer} 按三级规则裁决。索引是当前
     * 归一的唯一权威，故 ES 不可用时异常必须向上抛出，不可吞掉或返回空列表 —— 否则会把
     * 「索引挂了」误报成「词典无此词」。</p>
     *
     * @param type 术语类型
     * @param input 输入词，为空时直接返回空列表
     * @param maxCandidates 召回上限
     * @return 候选词条（按相关度排序）；无命中时为空列表
     * @throws IOException ES 检索失败或索引不存在
     */
    public List<TermEntry> search(String type, String input, int maxCandidates) throws IOException {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        // 1. 宽松 OR 召回，只取三个字段（source 只用于页面上标注出处）
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(maxCandidates)
                .query(recallQuery(input))
                .fetchSource(new String[]{"standard_term", "aliases", "source"}, null);

        SearchRequest request = new SearchRequest(indexName(type)).source(source);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

        // 2. 命中的文档逐条转成词条交给上层裁决（这里不做命中判定）
        List<TermEntry> candidates = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            candidates.add(toEntry(hit.getSourceAsMap()));
        }
        log.debug("[ES] {} 召回 {} 条候选（input={}）", indexName(type), candidates.size(), input);
        return candidates;
    }

    /**
     * 召回查询：<b>只求不漏，不求准</b>——任一子句命中即入选（{@code minimum_should_match=1}）。
     *
     * <p>精确两路用 keyword（标准词 / 别名），近似两路用 IK 分词的 match 并放宽 fuzziness：
     * 保证「咽喉痛→咽痛」这类分词后仍有公共 token 的近似写法能被捞出来。真正的命中判定
     * 由 {@link com.tcm.ehr.common.utils.EsTermNormalizer} 的精确/包含/Dice 三级规则完成。</p>
     */
    private QueryBuilder recallQuery(String input) {
        return QueryBuilders.boolQuery()
                .should(QueryBuilders.termQuery("standard_term", input))
                .should(QueryBuilders.termQuery("aliases.keyword", input))
                .should(QueryBuilders.matchQuery("standard_term.text", input).fuzziness(Fuzziness.AUTO))
                .should(QueryBuilders.matchQuery("aliases", input).fuzziness(Fuzziness.AUTO))
                .minimumShouldMatch(1);
    }

    private TermEntry toEntry(Map<String, Object> src) {
        // 1. ES 字段名是下划线，转成词条对象
        TermEntry entry = new TermEntry();
        entry.setStandardTerm(str(src.get("standard_term")));
        entry.setAliases(toStringList(src.get("aliases")));
        entry.setSource(str(src.get("source")));
        return entry;
    }

    private List<String> toStringList(Object value) {
        // 1. 正常情况是数组
        if (value instanceof List<?> raw) {
            List<String> list = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
            return list;
        }
        // 2. null 给空列表
        if (value == null) {
            return new ArrayList<>();
        }
        // 3. 兼容别名曾被存成「空格拼接单串」的旧文档
        String s = String.valueOf(value).trim();
        return s.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(s.split("\\s+")));
    }

    private String str(Object value) {
        // ES 缺字段给空串而不是 null：词条字段不接受 null
        return value == null ? "" : String.valueOf(value);
    }

    /** 文档 _id：标准词 MD5，保证同一词重复导入是覆盖而非新增 */
    private String hashId(String standardTerm) {
        // 1. 正常走 MD5
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(standardTerm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // 2. MD5 不可用（不该发生）时退回 hashCode，保证 id 仍可用
            return Integer.toHexString(standardTerm.hashCode());
        }
    }
}
