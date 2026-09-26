package com.tcm.ehr.common.utils;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.tcm.ehr.common.config.LlmConfig;
import com.tcm.ehr.common.config.LlmConfigStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM 统一入口（Spring AI 2.0 承载）。
 *
 * <p><b>职责</b>：供 ②词典 PDF 转换 ③AI 助手 ④病历解读 ⑤复核预检意见 统一复用；
 * 双通道 {@code provider=ollama}（本机，无需 api-key）/ {@code provider=openai}（OpenAI 及兼容三方网关，
 * DeepSeek / 通义 / 智谱等改 {@code llm.base-url} 即可接入）。
 * <b>判定地基仍是规则引擎</b>——评分 / 分级 / 归一不依赖本类，本类只负责把规则结论叙述成人话。</p>
 *
 * <p><b>三条硬约束</b>（与《后续开发方案》§8.1 一致）：</p>
 * <ol>
 * <li><b>不阻塞启动</b>：{@code ChatClient} 懒构造，首次调用才装配；配置缺失或非法只降级，
 * 绝不抛到启动期。Spring AI 的 {@code OpenAi*AutoConfiguration} 在缺 api-key 时会急切校验凭据、
 * 让整个上下文启动失败，故 {@code spring.ai.model.*} 已统一置 {@code none}，模型改由本类自行构造。</li>
 * <li><b>不泄漏底层异常</b>：调用异常统一捕获 → 记 WARN → 返回 {@code null}，
 * 由调用方回退规则/模板兜底。<b>唯一例外是 {@link #probe}</b>——连通性探测的目的就是报出失败原因，
 * 由调用方负责脱敏后再返回给用户。</li>
 * <li><b>配置可变</b>：生效参数来自 {@link LlmConfigStore} 而非直接读 {@code application.yml}，
 * 存储的版本号变化时会丢弃旧 {@code ChatClient} 并按新参数重建，实现「保存即生效、无需重启」。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    /** 连通性探测用提示词：只求最小往返，不要模型长篇输出 */
    private static final String PROBE_PROMPT = "ping";

    /** 未配置超时时的兜底值（与 {@code llm.timeout} 默认值一致） */
    private static final int DEFAULT_TIMEOUT_MS = 60000;

    /**
     * 不重试的模板。Spring AI 默认重试模板连「连接被拒」也重试 3 次并指数退避
     * （实测 10s / 10s / 50s），一次探测要等 80 秒以上，远超前端 30 秒超时 ——
     * 用户只会看到一句无用的网络错误，而不是「连不上」这个真正的原因。
     * 本项目 LLM 属可选增强，失败即回退规则，故统一不重试：宁可快速失败，不要长时间占用请求线程。
     */
    private static final RetryTemplate NO_RETRY = new RetryTemplate(RetryPolicy.withMaxRetries(0));

    private final LlmConfigStore configStore;

    /** 懒构造；volatile + 双重检查，保证并发首次调用只装配一次 */
    private volatile ChatClient chatClient;

    /** 已按哪个配置版本装配过（成功或失败都算）；与 {@link LlmConfigStore#version()} 不等则需重建 */
    private volatile long builtVersion = -1;

    // ------------------------------------------------------------------ 对外

    /** 当前配置是否开启 LLM（{@code llm.enabled} 或运行时覆盖后的等效值） */
    public boolean isEnabled() {
        return configStore.get().enabled();
    }

    /** 当前是否可用：配置已开启且装配成功 */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /** 当前通道名，供日志与诊断展示 */
    public String provider() {
        return configStore.get().provider();
    }

    /**
     * 单轮对话。
     *
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userPrompt 用户输入
     * @return 模型输出；不可用或调用失败返回 {@code null}，调用方据此走降级路径
     */
    public String chat(String systemPrompt, String userPrompt) {
        // 1. 取客户端；不可用（未启用或配置非法）就降级，调用方据 null 走规则路径
        ChatClient client = resolve();
        if (client == null) {
            LlmConfig cfg = configStore.get();
            log.debug("[LLM] 不可用（enabled={}，provider={}），降级", cfg.enabled(), cfg.provider());
            return null;
        }
        try {
            // 2. 系统提示词可空，传空串而不是 null（部分模型不接受 null）
            return client.prompt()
                    .system(systemPrompt == null ? "" : systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            // 3. 调用失败也降级：LLM 是增强项，不能让它把主流程带崩
            log.warn("[LLM] 调用失败，降级：{}", e.getMessage());
            return null;
        }
    }

    /** 无系统提示词的单轮对话 */
    public String chat(String userPrompt) {
        return chat(null, userPrompt);
    }

    /**
     * 连通性探测：用<b>给定参数</b>（而非当前生效配置）
     * 装配模型并发一轮最小请求，让用户可以先试再存。
     *
     * <p>与 {@link #chat} 相反，本方法<b>不吞异常</b>——探测的意义就是把失败原因交给调用方。
     * 调用方必须对异常信息做脱敏（抹掉 api-key）后再回传。</p>
     *
     * @return 模型的回复内容
     * @throws IllegalStateException 参数非法或连接/鉴权失败
     */
    public String probe(LlmConfig cfg) {
        // 1. 用待保存的参数现装一个模型（不碰当前生效配置，用户可以先试再存）
        ChatModel model = buildModel(cfg);
        // 2. 发一轮最小请求；异常向上抛，失败原因要如实告诉用户
        String reply = ChatClient.builder(model).build()
                .prompt()
                .user(PROBE_PROMPT)
                .call()
                .content();
        return reply == null ? "" : reply;
    }

    // ------------------------------------------------------------------ Prompt 归档

    /**
     * 词典 PDF 转换的系统提示词（兑现功能设计第16节「LLM 即 ETL」）。
     *
     * <p>Prompt 统一固化在这里，不散落到各接口；调整走代码评审 + 文档同步。
     * 要求「只输出 JSON 数组」是为了让调用方无需容错即可解析——
     * 即便如此，调用方仍须剥离可能的 markdown 代码块包裹（模型不一定听话）。</p>
     */
    public static final String DICT_CONVERT_SYSTEM_PROMPT = """
            你是中医术语词典抽取器。用户会给你一段从国标 PDF 提取的纯文本，请抽取其中的术语条目。

            规则：
            1. 只输出一个 JSON 数组，不要任何解释文字，不要 markdown 代码块。
            2. 每个元素的格式为：{"standardTerm":"标准术语","aliases":["别名1","别名2"]}
            3. aliases 没有就给空数组 []，不要用 null。
            4. 只抽取原文真实存在的术语，绝不臆造、不翻译、不补全。
            5. 无法确定标准术语的片段直接省略，不要猜测。
            6. 标准术语保持原文用字，不要改写。
            """;

    /**
     * AI 质控解读系统提示词：规则出结论，LLM 只负责叙述与要点摘要。
     *
     * <p>要求「严格输出 JSON」便于调用方解析 summary 段；模型不一定听话，
     * 调用方仍须剥离可能包裹的 markdown 代码块，解析失败则只取原文作 narrative。</p>
     */
    public static final String AI_INTERPRET_SYSTEM_PROMPT = """
            你是中医电子病历质控助理。用户会给你一条病历的「规则预检结论」与「结构化数据」。
            你只做两件事：
            1. 提取四要点：主诉(chiefComplaint) / 诊断(diagnosis) / 辨证(syndrome) / 方药(prescription)；
            2. 用简洁、专业、克制的中文，把规则结论叙述成一段质控解读。
            硬性要求：
            - 判定以规则结论为准，绝不臆造、不改变规则给出的判定与数据；
            - 缺失的业务字段就如实说缺失，不要编造内容；
            - 只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块；
            - 格式：{"summary":{"chiefComplaint":"","diagnosis":"","syndrome":"","prescription":""},"narrative":""}
            """;

    /**
     * AI 助手系统提示词：面向使用者的项目业务问答，不答技术实现。
     */
    public static final String AI_CHAT_SYSTEM_PROMPT = """
            你是「中医电子病历质控与标准化系统」的使用助手，面向临床与质控使用者。
            只回答与本系统相关的问题：数据统计、功能用法、业务流程、质控判定原因。
            硬性要求：
            - 不回答技术实现细节（框架、代码、数据库结构、接口实现等）；遇到这类问题，
              回复：这属于系统实现细节，建议查看设计文档或咨询开发同学。
            - 不臆造数据；上下文没给的数据就说查不到，不要编造。
            - 回答简洁（一般不超过 200 字），中文作答。
            """;

    /**
     * AI 复核预检系统提示词：基于规则预检单生成复核建议，判定仍以规则为准。
     */
    public static final String AI_REVIEW_SYSTEM_PROMPT = """
            你是中医电子病历质控复核助手。用户会给你一条病历的「规则预检单」与「结构化数据」。
            请给出简洁的复核建议，聚焦三点：疑似缺失的字段、证候与治法/方剂是否存在逻辑冲突、
            建议人工重点核对之处。
            硬性要求：
            - 判定以规则预检单为准，绝不臆造、不改变规则给出的结论；
            - 不要编造预检单里没有的扣分或冲突；
            - 中文作答，分点列出，一般不超过 200 字。
            """;


    // ------------------------------------------------------------------ 装配

    /**
     * 取当前生效的 {@code ChatClient}；配置版本变化时按新参数重建。
     *
     * @return 可用客户端；未启用或装配失败返回 {@code null}
     */
    private ChatClient resolve() {
        LlmConfig cfg = configStore.get();
        // 1. 未启用直接给 null，调用方走降级
        if (!cfg.enabled()) {
            return null;
        }
        long v = configStore.version();
        // 2. 配置没变就复用已装配的（null 也算"试过了"，避免每次调用都重试装配）
        if (v == builtVersion) {
            return chatClient;
        }
        // 3. 配置变了才重建；双检锁：并发的首次调用只装配一次
        synchronized (this) {
            if (v != builtVersion) {
                chatClient = null;
                try {
                    chatClient = ChatClient.builder(buildModel(cfg)).build();
                    log.info("[LLM] 已启用：provider={}，model={}", cfg.provider(),
                            isBlank(cfg.model()) ? "(通道默认)" : cfg.model());
                } catch (Exception e) {
                    // 装配失败保留 null，本次配置下都降级
                    log.warn("[LLM] 装配失败，本次配置下降级（不影响主流程）：{}", e.getMessage());
                }
                // 4. 无论成败都记下版本，避免坏配置被反复重试
                builtVersion = v;
            }
            return chatClient;
        }
    }

    /** 按给定参数装配底层模型；参数非法直接抛出（由调用方决定降级还是上报） */
    private ChatModel buildModel(LlmConfig cfg) {
        // 1. 先归一 provider（大小写/别名统一），不合法直接抛
        String provider = LlmConfig.normalizeProvider(cfg.provider());
        if (provider == null) {
            throw new IllegalStateException("llm.provider 非法：" + cfg.provider() + "（仅支持 ollama / openai）");
        }
        // 2. 按通道分派
        return switch (provider) {
            case LlmConfig.PROVIDER_OLLAMA -> buildOllama(cfg);
            case LlmConfig.PROVIDER_OPENAI -> buildOpenAi(cfg);
            default -> throw new IllegalStateException("llm.provider 非法：" + cfg.provider());
        };
    }

    /** 本机通道：无需 api-key；Ollama 未启动时在调用期失败并降级 */
    private ChatModel buildOllama(LlmConfig cfg) {
        // 1. 没配 base-url 就用本机默认
        String baseUrl = isBlank(cfg.baseUrl()) ? DEFAULT_OLLAMA_BASE_URL : cfg.baseUrl().trim();

        // OllamaApi 默认不设超时，连不上时会一直挂着；必须显式给连接与读取超时
        // 2. 显式设连接/读取超时
        int timeout = cfg.timeout() > 0 ? cfg.timeout() : DEFAULT_TIMEOUT_MS;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));

        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();

        // 3. 模型名与温度只在配了才设：留空即用通道默认值
        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        if (!isBlank(cfg.model())) {
            options.model(cfg.model().trim());
        }
        if (cfg.temperature() != null) {
            options.temperature(cfg.temperature());
        }
        // 4. NO_RETRY：不退避重试，失败就快失败，交给上层降级
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(options.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .retryTemplate(NO_RETRY)
                .build();
    }

    /** OpenAI 兼容通道：只认 OpenAI 协议，base-url 指向三方网关即可复用 */
    private ChatModel buildOpenAi(LlmConfig cfg) {
        // 1. 缺密钥直接抛：没有凭据的请求注定 401，不如早失败
        if (isBlank(cfg.apiKey())) {
            throw new IllegalStateException("provider=openai 但 api-key 为空");
        }
        SpringAiOpenAiHttpClient.Builder http = SpringAiOpenAiHttpClient.builder();
        if (cfg.timeout() > 0) {
            http.timeout(Duration.ofMillis(cfg.timeout()));
        }

        // 2. 显式给 base-url 就能指向三方网关复用（不限官方 OpenAI）
        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .apiKey(cfg.apiKey().trim())
                .maxRetries(0)          // 与 Ollama 通道同口径：快速失败，不做退避重试
                .httpClient(http.build());
        if (!isBlank(cfg.baseUrl())) {
            clientOptions.baseUrl(cfg.baseUrl().trim());
        }
        ClientOptions options = clientOptions.build();

        // 3. 模型名与温度可选，留空用通道默认
        OpenAiChatOptions.Builder chatOptions = OpenAiChatOptions.builder();
        if (!isBlank(cfg.model())) {
            chatOptions.model(cfg.model().trim());
        }
        if (cfg.temperature() != null) {
            chatOptions.temperature(cfg.temperature());
        }
        // 同步与异步两个客户端都要给。OpenAiChatModel.build() 在缺异步客户端时会回退到
        // OpenAiSetup 按 spring.ai.openai.* 自行装配，而本项目该前缀已统一置 none，
        // 于是「api-key 明明填了」也会抛 At least one credential source must be specified
        // —— 即 openai 通道永远连不上，且错误信息把矛头指向凭据、极难定位。
        return OpenAiChatModel.builder()
                .openAiClient(new OpenAIClientImpl(options))
                .openAiClientAsync(new OpenAIClientAsyncImpl(options))
                .options(chatOptions.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
