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
 *
 * 组织级隔离：同一 term_{type} 内全部组织的词条共存，用 org_id
 *
 * 字段区分（""=基础层）。检索时按 org_id IN ('', currentOrg) 过滤，
 * 保证 A 组织的词条不会命中到 B 组织的归一结果，同时保证基础层词典全组织可用。
 *
 *
 * 文档 _id 改为 MD5(orgId|standardTerm)：否则同名标准词（不同组织）
 *
 * 会互相覆盖。重建某组织时改用 delete_by_query 删除该组织的文档，而不是删整索引
 * —— 那样会把其它组织刚灌好的词条也一并删掉。
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
                // 失败必须让整个重建失败：吞掉的话该组织的旧文档会留在索引里继续参与归一，
                // 而 indexed_version 照样会被记为最新 —— 词典里已删掉的词还在命中，且无任何信号
                throw new IOException("清空该组织旧文档失败，已中止重建以免留下混合索引", e);
            }
        }

        BulkRequest bulk = new BulkRequest();
        for (TermEntry e : entries) {
            Map<String, Object> doc = new LinkedHashMap<>();
doc.put("standard_term", e.getStandardTerm());
                doc.put("aliases", e.getAliases() == null ? List.of() : e.getAliases());
                doc.put("source", e.getSource() == null ? "" : e.getSource());
                // 批次 22：编码随文档一起进索引，否则 toEntry 读不回 code、normCode 恒空。
                // 用 nullToEmpty 与 source 同口径 —— ES 文档字段不接受 null。
                doc.put("code", e.getCode() == null ? "" : e.getCode());
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
        // _source 白名单必须与写入侧（bulk 的 doc.put）逐字段对齐：
        // 少一个 code，toEntry 里的 src.get("code") 就永远是 null，
        // 表现为「词典页有编码、归一结果没有 normCode」，且不报错
        SearchSourceBuilder source = new SearchSourceBuilder()
                .size(maxCandidates)
                .query(recallQuery(org, input))
                .fetchSource(new String[]{"standard_term", "aliases", "source", "code", "org_id"}, null);

        SearchResponse response = client.search(new SearchRequest(indexName(type)).source(source), RequestOptions.DEFAULT);
        List<TermEntry> candidates = new ArrayList<>();
        for (SearchHit hit : response.getHits().getHits()) {
            candidates.add(toEntry(hit.getSourceAsMap()));
        }
        log.debug("[ES] {} 召回 {} 条候选（org_id='{}', input={}）", indexName(type), candidates.size(), org, input);
        return candidates;
    }

    /** 累计 ES 检索请求数（批次 12 · 12b 打点）：单条 search = +1，批量 msearch 整批 = +1 */
    private static final java.util.concurrent.atomic.AtomicLong SEARCH_REQUESTS =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public java.util.Map<String, List<TermEntry>> searchBatch(String type, String orgId, List<String> inputs,
                                                              int maxCandidates) throws IOException {
        java.util.Map<String, List<TermEntry>> out = new LinkedHashMap<>();
        if (inputs == null || inputs.isEmpty()) {
            return out;
        }
        String org = normalizeOrg(orgId);
        // 1. 去重（同批 LRU 的第一层）：同一批里同术语只查一次；空白项直接给空结果
        List<String> asked = new ArrayList<>();
        for (String in : inputs) {
            if (in == null || in.isBlank()) {
                out.put(in == null ? "" : in, List.of());
                continue;
            }
            if (!out.containsKey(in) && !asked.contains(in)) {
                asked.add(in);
            }
        }
        if (asked.isEmpty()) {
            return out;
        }
        // 2. 一次 _msearch：子查询与单条 search 逐字相同 ⇒ 结果与逐个查一致（结构上成立，非对账）
        org.elasticsearch.action.search.MultiSearchRequest req =
                new org.elasticsearch.action.search.MultiSearchRequest();
        for (String in : asked) {
            SearchSourceBuilder source = new SearchSourceBuilder()
                    .size(maxCandidates)
                    .query(recallQuery(org, in))
                    .fetchSource(new String[]{"standard_term", "aliases", "source", "code", "org_id"}, null);
            req.add(new SearchRequest(indexName(type)).source(source));
        }
        SEARCH_REQUESTS.addAndGet(1);
        org.elasticsearch.action.search.MultiSearchResponse resp = client.msearch(req, RequestOptions.DEFAULT);
        org.elasticsearch.action.search.MultiSearchResponse.Item[] items = resp.getResponses();
        for (int i = 0; i < asked.size(); i++) {
            List<TermEntry> candidates = new ArrayList<>();
            if (i < items.length && !items[i].isFailure() && items[i].getResponse() != null) {
                for (SearchHit hit : items[i].getResponse().getHits().getHits()) {
                    candidates.add(toEntry(hit.getSourceAsMap()));
                }
            }
            out.put(asked.get(i), candidates);
        }
        log.debug("[ES] {} 批量召回 {} 个术语（1 次 msearch，org_id='{}'）", indexName(type), asked.size(), org);
        return out;
    }

    @Override
    public long searchRequestCount() {
        return SEARCH_REQUESTS.get();
    }

    @Override
    public boolean schemaCompatible(String type) throws IOException {
        return exists(type) && hasCompatibleMapping(type);
    }

    /**
     * 索引的 mapping 是否与当前代码兼容。
     *
     *
     * 判据只有一条：org_id 必须是 keyword。基础层的 org_id 是空串，
     *
     * 而空串在 text（分词）字段里不进倒排索引，termsQuery 查不到
     * —— 表现就是全库归一「未收录」。从旧版本升级时，索引是旧代码建的（没有 org_id），
     * 写入时被 ES 动态映射成 text，正好踩中这一条。
     *
     *
     * 读不到 mapping 时按不兼容处理：宁可重建（幂等，只是多花一次灌数据），
     *
     * 也不要把一个查不出东西的索引留着继续用。
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
     * mapping 源里 org_id 与 code 是否都是 keyword（批次 22 起 code 也纳入判据）。
     *
     *
     * 抽成纯函数是为了能脱离 ES 单测 —— 这条判据是「基础层能不能被查到」
     *
     * 与「编码能不能读出来」的开关，值得有一个不走集群的回归测试兜住。
     *
     * @param mappingSource mapping 的 source map（形如 {"_meta":…, "properties":…}）
     * @return 所需字段都存在且类型为 keyword 时返回 true
     */
    static boolean orgIdIsKeyword(Map<String, Object> mappingSource) {
        if (mappingSource == null) {
            return false;
        }
        Object properties = mappingSource.get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return false;
        }
        return isKeyword(props.get("org_id")) && isKeyword(props.get("code"));
    }

    /**
     * mapping 源里某个字段是否为 keyword。
     *
     *
     * 批次 22 起，判据除 org_id 外还要求 code 存在且是 keyword：旧索引里没有
     *
     * code 这个字段，若只查 org_id 会判成「兼容」而跳过重建，于是
     * 「词典页能看到编码、但归一结果里没有 normCode」——
     * 症状是数据看着正常、实际编码链路没通。
     */
    private static boolean isKeyword(Object field) {
        return field instanceof Map<?, ?> f && "keyword".equals(String.valueOf(f.get("type")));
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
        // 批次 22：编码用 keyword（等值查询用），不进 text 分词
        properties.put("code", Map.of("type", "keyword"));
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
          // 批次 22：读回编码。空串要转成 null（区别于「确实没有编码」），
          // 与 DictionaryServiceImpl.normalize 的空串归 null 同口径。
          String code = src.get("code") == null ? null : String.valueOf(src.get("code"));
          entry.setCode(code == null || code.isBlank() ? null : code);
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
     * null 变空串，其余原样转字符串（不 trim）。
     *
     *
     * ES 文档字段不接受 null：缺字段时给空串，而不是让 Jackson 写出 null。
     *
     * 与 AiServiceImpl.rawOrNull（保持 null）语义相反，与
     * GovernanceServiceImpl.toTrimmedOrNull（空串归 null）也不同。
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
