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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Python NLP服务HTTP客户端封装。
 *
 * <p>调用 {@code POST /api/nlp/extract} 并映射为 {@link NlpExtractVO}；
 * 服务未启动 / 调用异常 / {@code nlp.enabled=false} 时返回 {@code null}，
 * 由调用方降级为空 9 类（{@code modelAvailable=false}），不阻塞主流程。</p>
 *
 * <p><b>多实例（不引入微服务框架）</b>：{@code nlp.service-urls} 用逗号分隔配置多个 NLP 实例地址
 * （留空则回退单个 {@code nlp.service-url}）。{@link #extract} 按轮询选取起始实例，
 * 失败（连不上 / 超时 / 5xx）自动顺延到下一个，<b>全部失败才返回 null</b>；
 * 挂掉的实例进入短冷却（默认 10s）被跳过，避免每次请求都白等连接超时。
 * 这样「3 台里挂 1~2 台」不影响整体，运维侧只需改配置文件。</p>
 */
@Slf4j
@Component
public class PythonNlpClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /** 单实例地址（向后兼容）；{@link #serviceUrls} 为空时回退到它 */
    @Value("${nlp.service-url:http://localhost:8001}")
    private String serviceUrl;

    /** 多实例地址，逗号分隔；留空表示只用 {@link #serviceUrl} */
    @Value("${nlp.service-urls:}")
    private String serviceUrls;

    @Value("${nlp.timeout:30000}")
    private int timeoutMs;

    @Value("${nlp.enabled:false}")
    private boolean enabled;

    /** 健康探测超时（毫秒）。探测是页面加载时顺带问一句，不能照抽取的 30s 等，默认 2s */
    @Value("${nlp.probe-timeout:2000}")
    private int probeTimeoutMs;

    /** 失败实例短路冷却时长（毫秒）：期间轮询跳过它，到点自动重试恢复 */
    @Value("${nlp.instance-cooldown-ms:10000}")
    private long cooldownMs;

    /** 解析后的实例地址列表（@PostConstruct 里算一次） */
    private List<String> instances = List.of();

    /** 轮询游标：每次抽取从这里取起始实例，分散负载 */
    private final AtomicInteger cursor = new AtomicInteger();

    /** 失败实例的冷却截止时刻（毫秒时间戳）：{@code url -> downUntil} */
    private final Map<String, Long> downUntil = new ConcurrentHashMap<>();

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
     * 启动时解析实例列表并说清「抽取服务未启用」这件事。
     *
     * <p>{@code nlp.enabled} 是 {@code @Value} 静态配置，启动时即已知，所以只在这里
     * 打一次，不必每个请求重复刷屏。级别用 warn 而非 debug：这是配置事实，生产把
     * {@code com.tcm.ehr} 调回 info 后仍应可见 —— 诊断信息不该随日志级别一起消失。</p>
     */
    @PostConstruct
    public void reportDisabledOnStartup() {
        // 0. 先解析实例列表：多个用逗号分隔；为空回退单实例
        List<String> parsed = new ArrayList<>();
        if (serviceUrls != null) {
            for (String u : serviceUrls.split(",")) {
                String t = u.trim();
                if (!t.isEmpty()) {
                    parsed.add(t);
                }
            }
        }
        if (parsed.isEmpty() && serviceUrl != null && !serviceUrl.isBlank()) {
            parsed.add(serviceUrl.trim());
        }
        this.instances = List.copyOf(parsed);

        // 1. 启用时只记一行地址，出问题才需要看
        if (enabled) {
            log.info("[NLP] 抽取服务已启用，目标实例 {} 个：{}", instances.size(), instances);
            return;
        }
        // 2. 未启用要写清后果与开启方式：抽取页"抽不出东西"多半就是这条
        log.warn("[NLP] 抽取服务未启用（nlp.enabled=false）：本次抽取将返回空 9 类、"
                + "modelAvailable=false。术语归一仍在执行（NlpController.extract 内调 "
                + "EntityNormalizer.normalize），但上游无实体可归。启用方式：application.yml "
                + "置 nlp.enabled: true 并启动 python-nlp（{}）后重试。", instances);
    }

    /**
     * 调用 NLP实体抽取 POST /api/nlp/extract（多实例轮询 + 故障转移）。
     *
     * @param text 原始病历文本
     * @return 抽取结果；未启用 / 所有实例都不可用 / 异常时返回 null（调用方降级）
     */
    public NlpExtractVO extract(String text) {
        // 1. 未启用直接返回 null：省掉一次注定失败的 HTTP
        if (!enabled) {
            log.debug("[NLP] 已禁用（nlp.enabled=false），跳过调用；本次降级为空 9 类，术语归一无可归内容");
            return null;
        }
        List<String> urls = instances;
        int n = urls.size();
        if (n == 0) {
            log.warn("[NLP] 未配置任何实例地址（nlp.service-urls / nlp.service-url），降级为空 9 类");
            return null;
        }
        // 2. 轮询选起始实例，之后顺延；连不上/超时/5xx 的实例进入冷却
        int start = Math.floorMod(cursor.getAndIncrement(), n);
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("text", text));
        } catch (Exception e) {
            log.warn("[NLP] 请求体序列化失败: {}", e.getMessage());
            return null;
        }
        for (int i = 0; i < n; i++) {
            String url = urls.get((start + i) % n);
            // 3. 冷却中的实例直接跳过（避免每次白等 3s 连接超时）
            Long down = downUntil.get(url);
            if (down != null && down > System.currentTimeMillis()) {
                continue;
            }
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
                    downUntil.remove(url);
                    return mapper.readValue(response.body(), NlpExtractVO.class);
                }
                // 4xx 是请求本身的问题（如文本超长 422），换实例也一样 —— 立即降级
                if (code >= 400 && code < 500) {
                    log.warn("[NLP] 调用失败: HTTP {}（{}），请求本身有问题，不再换实例，降级为空 9 类",
                            code, url);
                    return null;
                }
                // 5xx：该实例异常，冷却并顺延下一个
                markDown(url);
                log.warn("[NLP] 实例 {} 返回 HTTP {}，冷却 {}ms 后顺延下一实例", url, code, cooldownMs);
            } catch (Exception e) {
                // 连不上 / 超时 / 解析失败：冷却并顺延下一个
                markDown(url);
                log.warn("[NLP] 实例 {} 调用失败（{}），冷却 {}ms 后顺延下一实例",
                        url, e.getMessage(), cooldownMs);
            }
        }
        // 6. 所有实例都不可用：整体降级
        log.warn("[NLP] {} 个实例均不可用，本次降级为空 9 类、modelAvailable=false，术语归一无可归内容", n);
        return null;
    }

    /** 标记实例进入冷却（到点自动恢复）。 */
    private void markDown(String url) {
        if (cooldownMs > 0) {
            downUntil.put(url, System.currentTimeMillis() + cooldownMs);
        }
    }

    /**
     * 探测抽取服务状态（GET {serviceUrl}/health），多实例聚合。
     *
     * <p>与 {@link #extract(String)} 的分工：那条路要等模型推理，失败只能回 null；
     * 这条路只问「在不在、模型有没有加载」，超时短、不写业务日志、**绝不抛异常**，
     * 页面加载时想调就调。判定口径与 extract 保持一致：开关没开 = 没启用，
     * 全部实例 HTTP 不通或非 200 = 连不上，通了但都 modelAvailable=false = 模型未加载。</p>
     *
     * @return 探测结论（聚合）；任何失败都落成「连不上」，不向上抛
     */
    public NlpHealthVO probe() {
        NlpHealthVO health = new NlpHealthVO();
        health.setEnabled(enabled);
        // 1. 开关没开：不必发这次注定失败的 HTTP，原因直接给「未启用」
        if (!enabled) {
            health.setUnavailableReason(NlpExtractVO.REASON_DISABLED);
            return health;
        }
        List<String> urls = instances;
        if (urls.isEmpty()) {
            health.setUnavailableReason(NlpExtractVO.REASON_UNREACHABLE);
            return health;
        }
        // 2. 逐个探测：只要有一台可达且模型可用，即视为整体可用
        boolean anyReachable = false;
        boolean anyModel = false;
        for (String url : urls) {
            Boolean model = probeOne(url);
            if (model != null) {
                anyReachable = true;
                if (model) {
                    anyModel = true;
                }
            }
        }
        if (!anyReachable) {
            health.setUnavailableReason(NlpExtractVO.REASON_UNREACHABLE);
            return health;
        }
        health.setReachable(true);
        health.setModelAvailable(anyModel);
        if (!anyModel) {
            health.setUnavailableReason(NlpExtractVO.REASON_MODEL_MISSING);
        }
        return health;
    }

    /**
     * 探测单个实例；返回是否加载了模型（null 表示该实例不可达/非 200）。
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
