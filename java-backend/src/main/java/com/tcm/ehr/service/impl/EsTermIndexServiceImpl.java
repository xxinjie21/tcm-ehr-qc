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
    public String indexName(String type) {
        return INDEX_PREFIX + type;
    }

    @Override
    public boolean exists(String type) throws IOException {
        return client.indices().exists(new GetIndexRequest(indexName(type)), RequestOptions.DEFAULT);
    }

    @Override
    public void rebuild(String type, List<TermEntry> entries) throws IOException {
        String index = indexName(type);
        if (exists(type)) {
            client.indices().delete(new DeleteIndexRequest(index), RequestOptions.DEFAULT);
        }
        CreateIndexRequest create = new CreateIndexRequest(index);
        create.settings(Map.of("number_of_shards", 1, "number_of_replicas", 0));
        create.mapping(Map.of("properties", mappingProperties()));

        client.indices().create(create, RequestOptions.DEFAULT);

        BulkRequest bulk = new BulkRequest();
        for (TermEntry e : entries) {
            // 交给 Jackson 生成文档 JSON，避免手写转义在别名含引号/反斜杠时出错
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("standard_term", e.getStandardTerm());
            doc.put("aliases", e.getAliases() == null ? List.of() : e.getAliases());
            doc.put("source", e.getSource() == null ? "" : e.getSource());
            bulk.add(new IndexRequest(index).id(hashId(e.getStandardTerm()))
                    .source(objectMapper.writeValueAsString(doc), XContentType.JSON));
        }
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

        Map<String, Object> standardTerm = new LinkedHashMap<>();
        standardTerm.put("type", "keyword");
        standardTerm.put("fields", Map.of("text", Map.of("type", "text", "analyzer", "ik_max_word")));
        properties.put("standard_term", standardTerm);

        Map<String, Object> aliases = new LinkedHashMap<>();
        aliases.put("type", "text");
        aliases.put("analyzer", "ik_max_word");
        aliases.put("fields", Map.of("keyword", Map.of("type", "keyword")));
        properties.put("aliases", aliases);

        properties.put("source", Map.of("type", "keyword"));
        return properties;
    }

    @Override
    public List<TermEntry> search(String type, String input, int maxCandidates) throws IOException {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(maxCandidates)
                .query(recallQuery(input))
                .fetchSource(new String[]{"standard_term", "aliases", "source"}, null);

        SearchRequest request = new SearchRequest(indexName(type)).source(source);
        SearchResponse response = client.search(request, RequestOptions.DEFAULT);

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
        TermEntry entry = new TermEntry();
        entry.setStandardTerm(str(src.get("standard_term")));
        entry.setAliases(toStringList(src.get("aliases")));
        entry.setSource(str(src.get("source")));
        return entry;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> raw) {
            List<String> list = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item != null) {
                    list.add(String.valueOf(item));
                }
            }
            return list;
        }
        // 兼容别名曾被存成「空格拼接单串」的旧文档
        if (value == null) {
            return new ArrayList<>();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(s.split("\\s+")));
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String hashId(String standardTerm) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(standardTerm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(standardTerm.hashCode());
        }
    }
}
