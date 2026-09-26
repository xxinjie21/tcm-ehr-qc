package com.tcm.ehr.common.utils;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import jakarta.annotation.PostConstruct;
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
 * Python NLP服务HTTP客户端封装。
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
     * 启动时把「抽取服务未启用」这件事说清楚。
     *
     * <p>{@code nlp.enabled} 是 {@code @Value} 静态配置，启动时即已知，所以只在这里
     * 打一次，不必每个请求重复刷屏。级别用 warn 而非 debug：这是配置事实，生产把
     * {@code com.tcm.ehr} 调回 info 后仍应可见 —— 诊断信息不该随日志级别一起消失。</p>
     *
     * <p>这段技术细节原先写在解析页的降级横幅上，用户看不懂；改为
     * 横幅只说人话、细节落在这里。</p>
     */
    @PostConstruct
    public void reportDisabledOnStartup() {
        // 1. 启用时只记一行地址，出问题才需要看
        if (enabled) {
            log.info("[NLP] 抽取服务已启用，目标地址 {}", serviceUrl);
            return;
        }
        // 2. 未启用要写清后果与开启方式：抽取页"抽不出东西"多半就是这条
        log.warn("[NLP] 抽取服务未启用（nlp.enabled=false）：本次抽取将返回空 9 类、"
                + "modelAvailable=false。术语归一仍在执行（NlpController.extract 内调 "
                + "EntityNormalizer.normalize），但上游无实体可归。启用方式：application.yml "
                + "置 nlp.enabled: true 并启动 python-nlp（{}）后重试。", serviceUrl);
    }

    /**
     * 调用 NLP实体抽取 POST /api/nlp/extract
     *
     * @param text 原始病历文本
     * @return 抽取结果；未启用 / 服务不可用 / 异常时返回 null（调用方降级）
     */
    public NlpExtractVO extract(String text) {
        // 1. 未启用直接返回 null：省掉一次注定失败的 HTTP
        if (!enabled) {
            log.debug("[NLP] 已禁用（nlp.enabled=false），跳过调用；本次降级为空 9 类，术语归一无可归内容");
            return null;
        }
        try {
            // 2. POST 文本到抽取服务
            String body = mapper.writeValueAsString(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl + "/api/nlp/extract"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 3. 非 200 一律降级，不解析错误响应体
            if (response.statusCode() != 200) {
                log.warn("[NLP] 调用失败: HTTP {}（{}），已降级为空 9 类、modelAvailable=false，术语归一无可归内容",
                        response.statusCode(), serviceUrl);
                return null;
            }
            return mapper.readValue(response.body(), NlpExtractVO.class);
        } catch (Exception e) {
            // 4. 连不上/超时/解析失败都降级：抽取是增强项，不能拖垮主流程
            log.warn("[NLP] 调用异常（{}），已降级为空 9 类、modelAvailable=false，术语归一无可归内容: {}",
                    serviceUrl, e.getMessage());
            return null;
        }
    }
}
