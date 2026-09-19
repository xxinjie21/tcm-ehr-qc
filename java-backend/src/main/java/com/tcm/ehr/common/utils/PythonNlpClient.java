package com.tcm.ehr.common.utils;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Python NLP服务HTTP客户端封装（成员B的FastAPI服务，端口8001）
 * 服务未部署时返回null并记录告警，不阻塞主流程（降级开关 nlp.enabled=false 可完全关闭调用）
 */
@Slf4j
@Component
public class PythonNlpClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @Value("${nlp.service-url:http://localhost:8001}")
    private String serviceUrl;

    @Value("${nlp.timeout:30000}")
    private int timeoutMs;

    @Value("${nlp.enabled:false}")
    private boolean enabled;

    public PythonNlpClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 调用 NLP实体抽取 POST /api/nlp/extract
     *
     * @param text 原始病历文本
     * @return 抽取结果JSON（结构化实体），服务不可用返回null
     */
    public Map<String, Object> extract(String text) {
        if (!enabled) {
            log.debug("[NLP] 已禁用（nlp.enabled=false），跳过调用");
            return null;
        }
        try {
            String body = mapper.writeValueAsString(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl + "/api/nlp/extract"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[NLP] 调用失败: HTTP {}", response.statusCode());
                return null;
            }
            return mapper.readValue(response.body(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            log.warn("[NLP] 调用异常: {}", e.getMessage());
            return null;
        }
    }
}
