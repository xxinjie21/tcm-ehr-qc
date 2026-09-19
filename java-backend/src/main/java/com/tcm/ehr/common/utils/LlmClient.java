package com.tcm.ehr.common.utils;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.tcm.ehr.common.config.LlmProperties;
import io.micrometer.observation.ObservationRegistry;
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
 * <p><b>两条硬约束</b>（与《后续开发方案》§8.1 一致）：</p>
 * <ol>
 *   <li><b>不阻塞启动</b>：{@code ChatClient} 懒构造，首次调用才装配；配置缺失或非法只降级，
 *       绝不抛到启动期。Spring AI 的 {@code OpenAi*AutoConfiguration} 在缺 api-key 时会急切校验凭据、
 *       让整个上下文启动失败，故 {@code spring.ai.model.*} 已统一置 {@code none}，模型改由本类自行构造。</li>
 *   <li><b>不泄漏底层异常</b>：调用异常统一捕获 → 记 WARN → 返回 {@code null}，
 *       由调用方回退规则/模板兜底。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final LlmProperties props;

    /** 懒构造；volatile + 双重检查，保证并发首次调用只装配一次 */
    private volatile ChatClient chatClient;

    /** 装配失败（配置类错误）后本次运行内不再重试，避免每次调用都重复报错刷屏 */
    private volatile boolean initFailed;

    // ------------------------------------------------------------------ 对外

    /** 当前是否可用：{@code llm.enabled=true} 且装配成功 */
    public boolean isAvailable() {
        return resolve() != null;
    }

    /** 当前通道名，供日志与诊断展示 */
    public String provider() {
        return props.getProvider();
    }

    /**
     * 单轮对话。
     *
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userPrompt   用户输入
     * @return 模型输出；不可用或调用失败返回 {@code null}，调用方据此走降级路径
     */
    public String chat(String systemPrompt, String userPrompt) {
        ChatClient client = resolve();
        if (client == null) {
            log.debug("[LLM] 不可用（llm.enabled={}，provider={}），降级", props.isEnabled(), props.getProvider());
            return null;
        }
        try {
            return client.prompt()
                    .system(systemPrompt == null ? "" : systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[LLM] 调用失败，降级：{}", e.getMessage());
            return null;
        }
    }

    /** 无系统提示词的单轮对话 */
    public String chat(String userPrompt) {
        return chat(null, userPrompt);
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
     * AI 质控解读系统提示词（批C·3.1）：规则出结论，LLM 只负责叙述与要点摘要。
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
     * AI 助手系统提示词（批C·3.2）：面向使用者的项目业务问答，不答技术实现。
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
     * AI 复核预检系统提示词（批D·5.1）：基于规则预检单生成复核建议，判定仍以规则为准。
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

    private ChatClient resolve() {
        if (!props.isEnabled() || initFailed) {
            return null;
        }
        ChatClient local = chatClient;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (chatClient != null) {
                return chatClient;
            }
            try {
                chatClient = build();
                log.info("[LLM] 已启用：provider={}，model={}", props.getProvider(),
                        isBlank(props.getModel()) ? "(通道默认)" : props.getModel());
            } catch (Exception e) {
                initFailed = true;
                log.warn("[LLM] 装配失败，本次运行内降级（不影响主流程）：{}", e.getMessage());
            }
            return chatClient;
        }
    }

    private ChatClient build() {
        String provider = props.getProvider() == null ? "" : props.getProvider().trim().toLowerCase();
        ChatModel model = switch (provider) {
            case PROVIDER_OLLAMA -> buildOllama();
            case PROVIDER_OPENAI -> buildOpenAi();
            default -> throw new IllegalStateException(
                    "llm.provider 非法：" + props.getProvider() + "（仅支持 ollama / openai）");
        };
        return ChatClient.builder(model).build();
    }

    /** 本机通道：无需 api-key；Ollama 未启动时在调用期失败并降级 */
    private ChatModel buildOllama() {
        String baseUrl = isBlank(props.getBaseUrl()) ? DEFAULT_OLLAMA_BASE_URL : props.getBaseUrl().trim();
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();

        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        if (!isBlank(props.getModel())) {
            options.model(props.getModel().trim());
        }
        if (props.getTemperature() != null) {
            options.temperature(props.getTemperature());
        }
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(options.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    /** OpenAI 兼容通道：只认 OpenAI 协议，base-url 指向三方网关即可复用 */
    private ChatModel buildOpenAi() {
        if (isBlank(props.getApiKey())) {
            throw new IllegalStateException("llm.provider=openai 但 llm.api-key 为空");
        }
        SpringAiOpenAiHttpClient.Builder http = SpringAiOpenAiHttpClient.builder();
        if (props.getTimeout() > 0) {
            http.timeout(Duration.ofMillis(props.getTimeout()));
        }

        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .apiKey(props.getApiKey().trim())
                .httpClient(http.build());
        if (!isBlank(props.getBaseUrl())) {
            clientOptions.baseUrl(props.getBaseUrl().trim());
        }
        OpenAIClient openAi = new OpenAIClientImpl(clientOptions.build());

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder();
        if (!isBlank(props.getModel())) {
            options.model(props.getModel().trim());
        }
        if (props.getTemperature() != null) {
            options.temperature(props.getTemperature());
        }
        return OpenAiChatModel.builder()
                .openAiClient(openAi)
                .options(options.build())
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
