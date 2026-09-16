package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.xcontent.XContentType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * ES术语索引服务实现：term_{type} 索引构建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsTermIndexServiceImpl implements IEsTermIndexService {

    public static final String INDEX_PREFIX = "term_";

    private final RestHighLevelClient client;

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
        Map<String, Object> properties = new java.util.HashMap<>();
        // keyword精确 + text子字段（IK分词）用于模糊归一
        Map<String, Object> standardTerm = new java.util.HashMap<>();
        standardTerm.put("type", "keyword");
        standardTerm.put("fields", Map.of("text", Map.of("type", "text", "analyzer", "ik_max_word")));
        properties.put("standard_term", standardTerm);
        properties.put("aliases", Map.of("type", "text", "analyzer", "ik_max_word"));
        properties.put("source", Map.of("type", "keyword"));
        create.mapping(Map.of("properties", properties));
        client.indices().create(create, RequestOptions.DEFAULT);

        BulkRequest bulk = new BulkRequest();
        for (TermEntry e : entries) {
            String doc = """
                    {"standard_term":"%s","aliases":"%s","source":"%s"}""".formatted(
                    escape(e.getStandardTerm()),
                    escape(String.join(" ", e.getAliases() == null ? List.of() : e.getAliases())),
                    escape(e.getSource() == null ? "" : e.getSource()));
            bulk.add(new IndexRequest(index).id(hashId(e.getStandardTerm()))
                    .source(doc, XContentType.JSON));
        }
        if (bulk.numberOfActions() > 0) {
            client.bulk(bulk, RequestOptions.DEFAULT);
        }
        log.info("[ES] 索引 {} 重建完成，术语数: {}", index, entries.size());
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
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
