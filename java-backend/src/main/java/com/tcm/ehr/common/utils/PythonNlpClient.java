package com.tcm.ehr.common.utils;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpHealthVO;
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
 * Python NLP服务HTTP客户端封装（单实例）。
 *
 * <p>调用 {@code POST {nlp.service-url}/api/nlp/extract} 并映射为 {@link NlpExtractVO}；
 * 服务未启动 / 调用异常 / {@code nlp.enabled=false} 时返回 {@code null}，
 * 由调用方降级为空 9 类（{@code modelAvailable=false}），不阻塞主流程。</p>
 *
 * <p>本机单实例即可：torch 推理是 CPU 密集型，同一台机器多起实例只是把同样的核拆着用、
 * 吞吐不增反而内存翻倍，也没有跨机故障转移可言。并发靠
 * {@code nlp.batch.concurrency}（批量侧线程池）与 Python 侧的
 * {@code NLP_INFER_CONCURRENCY} 承担，不需要在此轮询分发。</p>
 */
@Slf4j
@Component
public class PythonNlpClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /** NLP 实例地址 */
    @Value("${nlp.service-url:http://localhost:8001}")
    private String serviceUrl;

    @Value("${nlp.timeout:30000}")
    private int timeoutMs;

    @Value("${nlp.enabled:false}")
    private boolean enabled;

    /** 健康探测超时（毫秒）。探测是页面加载时顺带问一句，不能照抽取的 30s 等，默认 2s */
    @Value("${nlp.probe-timeout:2000}")
    private int probeTimeoutMs;

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
     * 启动时说清「抽取服务未启用」这件事。
     *
     * <p>{@code nlp.enabled} 是 {@code @Value} 静态配置，启动时即已知，所以只在这里
     * 打一次，不必每个请求重复刷屏。级别用 warn 而非 debug：这是配置事实，生产把
     * {@code com.tcm.ehr} 调回 info 后仍应可见 —— 诊断信息不该随日志级别一起消失。</p>
     */
    @PostConstruct
    public void reportOnStartup() {
        String url = (serviceUrl == null || serviceUrl.isBlank()) ? "(未配置)" : serviceUrl.trim();
        if (enabled) {
            log.info("[NLP] 抽取服务已启用，目标实例：{}", url);
            return;
        }
        log.warn("[NLP] 抽取服务未启用（nlp.enabled=false）：本次抽取将返回空 9 类、"
                + "modelAvailable=false。术语归一仍在执行（NlpController.extract 内调 "
                + "EntityNormalizer.normalize），但上游无实体可归。启用方式：application.yml "
                + "置 nlp.enabled: true 并启动 python-nlp（{}）后重试。", url);
    }

    /**
     * 调用 NLP实体抽取 POST /api/nlp/extract。
     *
     * @param text 原始病历文本
     * @return 抽取结果；未启用 / 实例不可用 / 异常时返回 null（调用方降级）
     */
    public NlpExtractVO extract(String text) {
        // 1. 未启用直接返回 null：省掉一次注定失败的 HTTP
        if (!enabled) {
            log.debug("[NLP] 已禁用（nlp.enabled=false），跳过调用；本次降级为空 9 类，术语归一无可归内容");
            return null;
        }
        if (serviceUrl == null || serviceUrl.isBlank()) {
            log.warn("[NLP] 未配置实例地址（nlp.service-url），降级为空 9 类");
            return null;
        }
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("text", text));
        } catch (Exception e) {
            log.warn("[NLP] 请求体序列化失败: {}", e.getMessage());
            return null;
        }
        String url = serviceUrl.trim();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/nlp/extract"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                return mapper.readValue(response.body(), NlpExtractVO.class);
            }
            // 4xx 是请求本身的问题（如文本超长 422），重试也没用 —— 立即降级
            if (code >= 400 && code < 500) {
                log.warn("[NLP] 调用失败: HTTP {}（{}），请求本身有问题，降级为空 9 类", code, url);
                return null;
            }
            log.warn("[NLP] 实例 {} 返回 HTTP {}，降级为空 9 类", url, code);
            return null;
        } catch (Exception e) {
            log.warn("[NLP] 实例 {} 调用失败（{}），降级为空 9 类", url, e.getMessage());
            return null;
        }
    }

    /**
     * 探测抽取服务状态（GET {serviceUrl}/health）。
     *
     * <p>与 {@link #extract(String)} 的分工：那条路要等模型推理，失败只能回 null；
     * 这条路只问「在不在、模型有没有加载」，超时短、不写业务日志、**绝不抛异常**，
     * 页面加载时想调就调。判定口径与 extract 保持一致：开关没开 = 没启用，
     * HTTP 不通或非 200 = 连不上，通了但 modelAvailable=false = 模型未加载。</p>
     *
     * @return 探测结论；任何失败都落成「连不上」，不向上抛
     */
    public NlpHealthVO probe() {
        NlpHealthVO health = new NlpHealthVO();
        health.setEnabled(enabled);
        // 1. 开关没开：不必发这次注定失败的 HTTP，原因直接给「未启用」
        if (!enabled) {
            health.setUnavailableReason(NlpExtractVO.REASON_DISABLED);
            return health;
        }
        if (serviceUrl == null || serviceUrl.isBlank()) {
            health.setUnavailableReason(NlpExtractVO.REASON_UNREACHABLE);
            return health;
        }
        // 2. 探测实例：可达且模型可用才算整体可用
        Boolean model = probeOne(serviceUrl.trim());
        if (model == null) {
            health.setUnavailableReason(NlpExtractVO.REASON_UNREACHABLE);
            return health;
        }
        health.setReachable(true);
        health.setModelAvailable(model);
        if (!model) {
            health.setUnavailableReason(NlpExtractVO.REASON_MODEL_MISSING);
        }
        return health;
    }

    /**
     * 探测实例；返回是否加载了模型（null 表示该实例不可达/非 200）。
     * 绝不抛异常。
     */
    private Boolean probeOne(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/health"))
                    .timeout(Duration.ofMillis(probeTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("[NLP] 健康探测 HTTP {}（{}），按连不上处理", response.statusCode(), url);
                return null;
            }
            Map<String, Object> body = mapper.readValue(response.body(),
                    new TypeReference<Map<String, Object>>() { });
            return Boolean.TRUE.equals(body.get("modelAvailable"));
        } catch (Exception e) {
            log.debug("[NLP] 健康探测失败（{}）: {}", url, e.getMessage());
            return null;
        }
    }
}
