package com.tcm.ehr.common.utils;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.domain.vo.NlpExtractVO;
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
 * Python NLP服务HTTP客户端封装（成员B的FastAPI服务，端口8001，批G·8.1）。
 *
 * <p>调用 {@code POST /api/nlp/extract} 并映射为 {@link NlpExtractVO}；
 * 服务未启动 / 调用异常 / {@code nlp.enabled=false} 时返回 {@code null}，
 * 由调用方降级为空 9 类（{@code modelAvailable=false}），不阻塞主流程。</p>
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
                .version(HttpClient.Version.HTTP_1_1)   // uvicorn 仅 HTTP/1.1，禁用默认的 h2c upgrade
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /** 抽取总开关（供导入链路判断是否触发抽取） */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 调用 NLP实体抽取 POST /api/nlp/extract
     *
     * @param text 原始病历文本
     * @return 抽取结果；未启用 / 服务不可用 / 异常时返回 null（调用方降级）
     */
    public NlpExtractVO extract(String text) {
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
            return mapper.readValue(response.body(), NlpExtractVO.class);
        } catch (Exception e) {
            log.warn("[NLP] 调用异常（将降级）: {}", e.getMessage());
            return null;
        }
    }
}
