package com.tcm.ehr.common.utils;

import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.tcm.ehr.common.utils.TextUtil;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 统一入口（Spring AI 2.0 承载）。
 *
 * <p><b>职责</b>：供 ①AI 助手 ②病历解读 ③复核预检意见 统一复用；
 * 双通道 {@code provider=ollama}（本机，无需 api-key）/ {@code provider=openai}（OpenAI 及兼容三方网关，
 * DeepSeek / 通义 / 智谱等改 {@code llm.base-url} 即可接入）。
 * <b>判定地基仍是规则引擎</b>——评分 / 分级 / 归一不依赖本类，本类只负责把规则结论叙述成人话。</p>
 *
 * <p><b>三条硬约束</b>（与《开发指南与待办》「二、开发规范 · 后端」一致）：</p>
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

    /** 未配置超时时的兜底值（与 {@code llm.timeout} 默认值一致，批次15 · 15.2 起为 20s 级） */
    private static final int DEFAULT_TIMEOUT_MS = 20000;

    /**
     * 不重试的模板。Spring AI 默认重试模板连「连接被拒」也重试 3 次并指数退避
     * （实测 10s / 10s / 50s），一次探测要等 80 秒以上，远超前端 30 秒超时 ——
     * 用户只会看到一句无用的网络错误，而不是「连不上」这个真正的原因。
     * 本项目 LLM 属可选增强，失败即回退规则，故统一不重试：宁可快速失败，不要长时间占用请求线程。
     */
    private static final RetryTemplate NO_RETRY = new RetryTemplate(RetryPolicy.withMaxRetries(0));

    private final LlmConfigStore configStore;

    /**
     * 已装配的客户端缓存：key = {@code userId|版本}。
     *
     * <p><b>为什么按用户缓存</b>：配置改成「每人一份」后，单个 {@code ChatClient}
     * 复用就会让 A 的请求发到 B 的通道上 —— 配置与客户端必须成对。</p>
     *
     * <p><b>为什么设上限</b>：每个 {@code ChatClient} 内部持有 HTTP 客户端与连接池，
     * 按用户无限增长等于把内存泄漏进来。</p>
     *
     * <p><b>为什么用 {@link VersionedCache} 而不是自己继承 LinkedHashMap</b>（批次14 审核）：
     * ①「有界 + LRU 逐出」这件事全仓已有 owner，再写一份就是两套逐出口径；
     * ②**更要紧的是线程安全** —— {@code resolve()} 跑在请求线程上，而
     * {@code LinkedHashMap(accessOrder=true)} 的 {@code get} 会改动内部访问序链表，
     * 并发 get/put 不加锁可能丢项、甚至死循环。VersionedCache 的读写都是 synchronized。</p>
     *
     * <p>键里已经带了配置版本（{@code userId|版本}），所以不需要版本比对那一套，用它的两段式接口：
     * {@code getIfPresent} → <b>锁外</b>装配 → {@code put}。装配必须在锁外，
     * 因为 {@code ChatClient.builder(...).build()} 会建 HTTP 连接池，是慢操作。</p>
     */
    private static final int CLIENT_CACHE_MAX = 20;
    private final VersionedCache<ChatClient> clients = new VersionedCache<>(CLIENT_CACHE_MAX);

    /** 记录「已经试过但装配失败」的 key，避免坏配置被反复重试 */
    private final Set<String> failedKeys = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ 对外

    /** <b>当前用户</b>的配置是否开启 LLM（未配置则看系统基线） */
    public boolean isEnabled() {
        return configStore.getFor(RequestUtils.currentUserId()).enabled();
    }

    /** 当前是否可用：配置已开启且装配成功 */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /** <b>当前用户</b>生效的通道名，供日志与诊断展示 */
    public String provider() {
        return configStore.getFor(RequestUtils.currentUserId()).provider();
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
            LlmConfig cfg = configStore.getFor(RequestUtils.currentUserId());
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

    /**
     * 术语补词建议系统提示词：在「现有词表召回候选」的约束下判断原文该怎么补。
     *
     * <p><b>为什么要把候选喂进来而不是让模型自由发挥</b>：模型凭记忆生成的标准词可能并不存在，
     * 它还会给出看起来很像的「出处」；而这些词一旦入库，归一就认它了 —— 等于用幻觉污染词表。
     * 给了候选，模型多数情况下只是在「系统已认的词」里做判断，可靠性完全不同。</p>
     *
     * <p>仍然要求「严格输出 JSON 数组」，与 {@link #AI_INTERPRET_SYSTEM_PROMPT} 同一套约定：
     * 模型不一定听话，调用方须剥离 markdown 围栏，解析失败则整体降级。</p>
     */
    public static final String AI_TERM_SUGGEST_SYSTEM_PROMPT = """
            你是中医术语标准化助手。用户会给你一批「待规范的原文词」，以及系统从现有词典里
            按字面相似度召回的「候选词」。请为每个原文词给出处理建议。
            硬性要求：
            - 优先在给出的候选词里选择；只有当候选里确实没有语义对应的词时，才依据中医术语规范
              给出建议的标准词（此时 source 必须是 model）。
            - 绝不编造术语编号、标准号或出处；不确定就选 ignore 并说明。
            - 只输出一个 JSON 数组，不要任何解释文字，不要 markdown 代码块。
            - 数组每个元素的格式：
              {"original":"原文","action":"alias|new|ignore","standardTerm":"","aliases":[],"source":"dict|model","reason":"一句话依据"}
            - action 的含义：
              · alias  —— 原文是某个已有标准词的口语说法或异名，应挂为该词的别名；
                          standardTerm 填候选里的那个标准词，source 填 dict。
              · new    —— 原文本身就是一个规范术语，只是词表漏收，应新建为标准词；
                          standardTerm 填该术语，source 填 model。
              · ignore —— 原文是抽取碎片、体征错放，或并非一个完整术语，不该进词表；
                          standardTerm 留空。
            - aliases 只在确有多个口语变体时填写，没有就给空数组。
            - 原文有几个，数组就必须有几个元素，顺序与输入一致。
            """;


    // ------------------------------------------------------------------ 装配

    /**
     * 取当前生效的 {@code ChatClient}；配置版本变化时按新参数重建。
     *
     * @return 可用客户端；未启用或装配失败返回 {@code null}
     */
    private ChatClient resolve() {
        // 1. 按当前用户解析配置：配置改成每人一份，客户端必须与配置成对
        String userId = RequestUtils.currentUserId();
        LlmConfig cfg = configStore.getFor(userId);
        // 2. 未启用直接给 null，调用方走降级
        if (!cfg.enabled()) {
            return null;
        }
        String key = userId + "|" + configStore.versionFor(userId);
        // 3. 配置没变就复用已装配的
        ChatClient hit = clients.getIfPresent(key);
        if (hit != null) {
            return hit;
        }
        if (failedKeys.contains(key)) {
            // 4. 这个配置试过且失败，不再重复装配（否则每次调用都打一次外部连接）
            return null;
        }
        // 5. 装配：并发下可能重复装一次，代价是浪费一次连接，不会出错（幂等）
        try {
            ChatClient client = ChatClient.builder(buildModel(cfg)).build();
            clients.put(key, client);
            log.info("[LLM] 已启用：user={}，provider={}，model={}", userId, cfg.provider(),
                    TextUtil.isBlank(cfg.model()) ? "(通道默认)" : cfg.model());
            return client;
        } catch (Exception e) {
            // 装配失败记下 key，本次配置下都降级（LLM 是增强项，不能带崩主流程）
            failedKeys.add(key);
            log.warn("[LLM] 装配失败，本次配置下降级（不影响主流程）：{}", e.getMessage());
            return null;
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
        String baseUrl = TextUtil.isBlank(cfg.baseUrl()) ? DEFAULT_OLLAMA_BASE_URL : cfg.baseUrl().trim();

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
        if (!TextUtil.isBlank(cfg.model())) {
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
        if (TextUtil.isBlank(cfg.apiKey())) {
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
        if (!TextUtil.isBlank(cfg.baseUrl())) {
            clientOptions.baseUrl(cfg.baseUrl().trim());
        }
        ClientOptions options = clientOptions.build();

        // 3. 模型名与温度可选，留空用通道默认
        OpenAiChatOptions.Builder chatOptions = OpenAiChatOptions.builder();
        if (!TextUtil.isBlank(cfg.model())) {
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

}
