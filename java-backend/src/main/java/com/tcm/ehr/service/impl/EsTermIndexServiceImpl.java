package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ES术语索引服务实现：term_{type} 索引的构建与检索（组织级）
 *
 * <p><b>组织级隔离</b>：同一 {@code term_{type}} 内全部组织的词条共存，用 {@code org_id}
 * 字段区分（{@code ""}=基础层）。检索时按 {@code org_id IN ('', currentOrg)} 过滤，
 * 保证 A 组织的词条不会命中到 B 组织的归一结果，同时保证基础层词典全组织可用。</p>
 *
 * <p>文档 {@code _id} 改为 {@code MD5(orgId|standardTerm)}：否则同名标准词（不同组织）
 * 会互相覆盖。重建某组织时改用 {@code delete_by_query} 删除该组织的文档，而不是删整索引
 * —— 那样会把其它组织刚灌好的词条也一并删掉。</p>
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
    public String indexedVersion(String type, String orgId) throws IOException {
        String index = indexName(type);
        if (!exists(type)) {
            return null;
        }
        GetMappingsResponse response = client.indices()
                .getMapping(new GetMappingsRequest().indices(index), RequestOptions.DEFAULT);
        MappingMetadata meta = response.mappings().get(index);
        if (meta == null) {
            return null;
        }
        Object m = meta.getSourceAsMap().get("_meta");
        if (m instanceof Map<?, ?> map && map.get("version") != null) {
            return String.valueOf(map.get("version"));
        }
        return null;
    }

    @Override
    public void rebuild(String type, String orgId, List<TermEntry> entries, String version) throws IOException {
        String index = indexName(type);
        String org = normalizeOrg(orgId);

        // ⚠️ 不能只判「索引存在」就复用：从旧版本升级时，索引是旧代码建的 ——
        //    它的 mapping 里没有 org_id（或 org_id 被动态映射成了 text）。
        //    复用会导致两个后果，且都不报错：
        //      1) delete_by_query(org_id) 删不掉任何东西（旧文档没这个字段）；
        //      2) bulk 写入的 org_id 被 ES 动态映射成 text（分词），于是空串
        //         ——也就是基础层的 org_id=""—— 根本不在倒排索引里，
        //         termsQuery("org_id","") 永远查不到 → 全库归一「未收录」。
        //    所以这里必须校验 mapping；不兼容就整索引重建（顺带清掉旧文档）。
        boolean recreate = !exists(type) || !hasCompatibleMapping(type);
        if (!exists(type)) {
            log.info("[ES] 索引 {} 不存在，创建", index);
        } else if (recreate) {
            log.warn("[ES] 索引 {} 的 mapping 不兼容（org_id 不是 keyword），删除重建", index);
            client.indices().delete(new org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest(index),
                    RequestOptions.DEFAULT);
        }

        if (recreate) {
            CreateIndexRequest create = new CreateIndexRequest(index);
            create.settings(Map.of("number_of_shards", 1, "number_of_replicas", 0));
            create.mapping(Map.of(
                    "_meta", Map.of("version", version == null ? "" : version),
                    "properties", mappingProperties()));
            client.indices().create(create, RequestOptions.DEFAULT);
        } else {
            // 先把该组织的旧文档删掉：不删整索引，避免误伤其它组织
            try {
                org.elasticsearch.client.Request r = new org.elasticsearch.client.Request("POST", index + "/_delete_by_query");
                r.setJsonEntity("{\"query\":{\"term\":{\"org_id\":\"" + escapeJson(org) + "\"}}}");
                client.getLowLevelClient().performRequest(r);
            } catch (Exception e) {
                log.warn("[ES] delete_by_query( org_id={}) 失败（索引可能有残留，继续 bulk）: {}", org, e.getMessage());
            }
        }

        BulkRequest bulk = new BulkRequest();
        for (TermEntry e : entries) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("standard_term", e.getStandardTerm());
            doc.put("aliases", e.getAliases() == null ? List.of() : e.getAliases());
            doc.put("source", e.getSource() == null ? "" : e.getSource());
            doc.put("org_id", org);
            bulk.add(new IndexRequest(index)
                    .id(hashId(org, e.getStandardTerm()))
                    .source(objectMapper.writeValueAsString(doc), XContentType.JSON));
        }
        if (bulk.numberOfActions() > 0) {
            client.bulk(bulk, RequestOptions.DEFAULT);
        }
        log.info("[ES] 索引 {} 重建完成，组织(org_id='{}')，术语数: {}", index, org, entries.size());
    }

    @Override
    public List<TermEntry> search(String type, String orgId, String input, int maxCandidates) throws IOException {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String org = normalizeOrg(orgId);
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(maxCandidates)
                .query(recallQuery(org, input))
                .fetchSource(new String[]{"standard_term", "aliases", "source", "org_id"}, null);

        SearchResponse response = client.search(new SearchRequest(indexName(type)).source(source), RequestOptions.DEFAULT);
        List<TermEntry> candidates = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            candidates.add(toEntry(hit.getSourceAsMap()));
        }
        log.debug("[ES] {} 召回 {} 条候选（org_id='{}', input={}）", indexName(type), candidates.size(), org, input);
        return candidates;
    }

    @Override
    public boolean schemaCompatible(String type) throws IOException {
        return exists(type) && hasCompatibleMapping(type);
    }

    /**
     * 索引的 mapping 是否与当前代码兼容。
     *
     * <p>判据只有一条：{@code org_id} 必须是 {@code keyword}。基础层的 org_id 是空串，
     * 而空串在 {@code text}（分词）字段里<b>不进倒排索引</b>，{@code termsQuery} 查不到
     * —— 表现就是全库归一「未收录」。从旧版本升级时，索引是旧代码建的（没有 org_id），
     * 写入时被 ES 动态映射成 text，正好踩中这一条。</p>
     *
     * <p>读不到 mapping 时按<b>不兼容</b>处理：宁可重建（幂等，只是多花一次灌数据），
     * 也不要把一个查不出东西的索引留着继续用。</p>
     */
    private boolean hasCompatibleMapping(String type) {
        try {
            GetMappingsResponse response = client.indices()
                    .getMapping(new GetMappingsRequest().indices(indexName(type)), RequestOptions.DEFAULT);
            MappingMetadata meta = response.mappings().get(indexName(type));
            if (meta == null) {
                return false;
            }
            return orgIdIsKeyword(meta.getSourceAsMap());
        } catch (Exception e) {
            log.warn("[ES] 读取 {} 的 mapping 失败，按不兼容处理（将重建）: {}",
                    indexName(type), e.getMessage());
            return false;
        }
    }

    /**
     * mapping 源里 {@code org_id} 是否为 {@code keyword}。
     *
     * <p>抽成纯函数是为了能脱离 ES 单测 —— 这条判据是「基础层能不能被查到」的开关，
     * 值得有一个不走集群的回归测试兜住。</p>
     *
     * @param mappingSource mapping 的 source map（形如 {@code {"_meta":…, "properties":…}}）
     * @return org_id 存在且类型为 keyword 时返回 true
     */
    static boolean orgIdIsKeyword(Map<String, Object> mappingSource) {
        if (mappingSource == null) {
            return false;
        }
        Object properties = mappingSource.get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return false;
        }
        Object orgId = props.get("org_id");
        return orgId instanceof Map<?, ?> field
                && "keyword".equals(String.valueOf(field.get("type")));
    }

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
        properties.put("org_id", Map.of("type", "keyword"));
        return properties;
    }

    private QueryBuilder recallQuery(String orgId, String input) {
        String org = normalizeOrg(orgId);
        return QueryBuilders.boolQuery()
                .filter(QueryBuilders.termsQuery("org_id", "", org))
                .should(QueryBuilders.termQuery("standard_term", input))
                .should(QueryBuilders.termQuery("aliases.keyword", input))
                .should(QueryBuilders.matchQuery("standard_term.text", input).fuzziness(Fuzziness.AUTO))
                .should(QueryBuilders.matchQuery("aliases", input).fuzziness(Fuzziness.AUTO))
                .minimumShouldMatch(1);
    }

    private TermEntry toEntry(Map<String, Object> src) {
        TermEntry entry = new TermEntry();
        entry.setStandardTerm(nullToEmpty(src.get("standard_term")));
        entry.setAliases(toStringList(src.get("aliases")));
        entry.setSource(nullToEmpty(src.get("source")));
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
        if (value == null) {
            return new ArrayList<>();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(s.split("\\s+")));
    }

    /**
     * null 变空串，其余原样转字符串（<b>不 trim</b>）。
     *
     * <p>ES 文档字段不接受 null：缺字段时给空串，而不是让 Jackson 写出 {@code null}。
     * 与 {@code AiServiceImpl.rawOrNull}（保持 null）语义相反，与
     * {@code GovernanceServiceImpl.toTrimmedOrNull}（空串归 null）也不同。</p>
     */
    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String hashId(String orgId, String standardTerm) {
        String key = (orgId == null ? "" : orgId) + "|" + (standardTerm == null ? "" : standardTerm);
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private String normalizeOrg(String orgId) {
        return orgId == null ? "" : orgId;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
