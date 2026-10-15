package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.AiFeature;
import com.tcm.ehr.common.config.LlmConfig;
import com.tcm.ehr.common.config.LlmConfigStore;
import com.tcm.ehr.mapper.UserLlmConfigMapper;
import com.tcm.ehr.common.config.LlmProperties;
import com.tcm.ehr.domain.po.UserLlmConfig;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClient 降级契约测试（纯对象构造，不加载 Spring 容器、不联网、不需要任何 api-key）。
 *
 * <p>锁定两条硬约束：<b>配置缺失/非法只降级不抛异常</b>（不阻塞主流程）、
 * <b>llm.enabled=false 时完全不装配模型</b>。一旦被破坏，要么主流程被 LLM 拖死，
 * 要么重演「缺 api-key 导致整个上下文启动失败」。</p>
 */
class LlmClientTest {

    /**
     * 每个用例一个**唯一且尚不存在**的配置文件路径。
     *
     * <p>必须隔离：{@link LlmConfigStore} 构造时会读 {@code llm.configFile} 并用其中的非密钥字段
     * 覆盖 yml 基线。默认路径是 {@code data/llm-config.json} —— 这是开发机上的真实运行时配置
     * （且被 .gitignore 忽略），用它会让断言变成「看本机文件内容」。</p>
     */
    @TempDir
    static Path configDir;

    private static final AtomicInteger CONFIG_SEQ = new AtomicInteger();

    private static LlmProperties props(boolean enabled, String provider) {
        LlmProperties p = new LlmProperties();
        p.setEnabled(enabled);
        p.setProvider(provider);
        p.setConfigFile(configDir.resolve("llm-config-" + CONFIG_SEQ.incrementAndGet() + ".json").toString());
        return p;
    }

    /** 测试用加密密钥：固定 32 字节 Base64，便于复现 */
    private static final String TEST_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static LlmSecretCipher cipher() {
        return new LlmSecretCipher(TEST_KEY);
    }

    /** 存储层测试替身：按内存 Map 模拟 user_llm_config（不碰数据库） */
    private static UserLlmConfigMapper emptyUserMapper() {
        return org.mockito.Mockito.mock(UserLlmConfigMapper.class);
    }

    private static LlmClient clientOf(LlmProperties p) {
        return new LlmClient(new LlmConfigStore(p, new ObjectMapper(),
                emptyUserMapper(), cipher()));
    }

    /** 关闭（默认态）：不装配、不可用，调用返回 null 且不抛 */
    @Test
    void disabled_shouldNotAssembleAndDegradeQuietly() {
        LlmClient client = clientOf(props(false, "ollama"));

        assertFalse(client.isAvailable(), "llm.enabled=false 时不应装配模型");
        assertNull(client.chat("随便问一句"), "关闭时应降级返回 null");
        assertNull(client.chat("系统提示", "用户输入"), "关闭时应降级返回 null");
    }

    /** provider 写错：装配失败被收敛为降级，不得抛异常 */
    @Test
    void illegalProvider_shouldDegradeInsteadOfThrowing() {
        LlmClient client = clientOf(props(true, "azure-openai-typo"));

        assertDoesNotThrow(client::isAvailable, "provider 非法不应抛异常");
        assertFalse(client.isAvailable(), "provider 非法应判为不可用");
        assertNull(client.chat("你好"), "provider 非法应降级返回 null");
    }

    /** 选 openai 但没填 api-key：降级，绝不抛（这正是自动装配会把启动搞崩的场景） */
    @Test
    void openaiWithoutApiKey_shouldDegradeInsteadOfThrowing() {
        LlmProperties p = props(true, "openai");
        p.setApiKey("");
        LlmClient client = clientOf(p);

        assertDoesNotThrow(client::isAvailable, "缺 api-key 不应抛异常");
        assertFalse(client.isAvailable(), "缺 api-key 应判为不可用");
        assertNull(client.chat("你好"), "缺 api-key 应降级返回 null");
    }

    /** 本机 Ollama 通道无需凭据，应当能装配成功 */
    @Test
    void ollamaWithoutApiKey_shouldAssemble() {
        LlmClient client = clientOf(props(true, "ollama"));

        assertTrue(client.isAvailable(), "ollama 通道不需要 api-key，应装配成功");
        assertTrue("ollama".equals(client.provider()));
    }

    /** Ollama 服务未启动（指向必然连不上的端口）：调用期失败也必须是降级，不得抛 */
    @Test
    void ollamaUnreachable_shouldDegradeOnCall() {
        LlmProperties p = props(true, "ollama");
        p.setBaseUrl("http://127.0.0.1:1");
        p.setTimeout(1000);
        LlmClient client = clientOf(p);

        assertTrue(client.isAvailable(), "装配不依赖网络，应仍可用");
        assertDoesNotThrow(() -> assertNull(client.chat("你好"), "连不上 Ollama 应降级返回 null"));
    }

    /**
     * 回归：openai 通道填了 api-key 也必须能装配到模型。
     *
     * <p>曾经的坑：只给 {@code OpenAiChatModel.Builder} 传同步客户端，build() 会回退到
     * {@code OpenAiSetup} 按 {@code spring.ai.openai.*} 自行装配，而该前缀已置 {@code none}，
     * 于是抛出 {@code At least one credential source must be specified} —— 看起来像「没填 key」，
     * 实际 key 一直都在。故此处断言失败原因里<b>不得出现凭据字样</b>。</p>
     */
    @Test
    void probeWithOpenAiKey_shouldNotFailOnCredentialAssembly() {
        LlmClient client = clientOf(props(false, "ollama"));
        LlmConfig cfg = new LlmConfig(true, "openai", "http://127.0.0.1:1/v1",
                "sk-test-abcdef123456", "gpt-4o-mini", 0.2D, 1000);

        Throwable t = assertThrows(Throwable.class, () -> client.probe(cfg),
                "连不上也应抛，供 /api/llm/test 报出原因");
        assertFalse(String.valueOf(t.getMessage()).contains("credential source"),
                "填了 api-key 就不该报缺凭据：实际信息 = " + t.getMessage());
    }

    /** probe 是唯一允许抛异常的入口：探测的意义就是把失败原因交给调用方 */
    @Test
    void probe_shouldThrowSoCallerCanReportReason() {
        LlmClient client = clientOf(props(false, "ollama"));

        assertThrows(IllegalStateException.class,
                () -> client.probe(new LlmConfig(true, "typo", "", "", "", null, 0)),
                "probe 遇非法参数应抛出，供 /api/llm/test 返回具体原因");
        assertThrows(IllegalStateException.class,
                () -> client.probe(new LlmConfig(true, "openai", "", "", "", null, 0)),
                "openai 通道缺 api-key 应抛出");
    }

    // ------------------------------------------------------------------ 功能开关门控

    /**
     * 总开关开着、模型也装配成功，但某项功能被单独关闭时：
     * 该功能的调用必须直接降级返回 {@code null}（调用方走规则兜底），不得打到模型。
     */
    @Test
    void featureSwitchOffDegradesOnlyThatFeature() {
        Map<String, UserLlmConfig> rows = new ConcurrentHashMap<>();
        UserLlmConfig row = new UserLlmConfig();
        row.setUserId("u-feat");
        row.setEnabled(true);
        row.setProvider("ollama");
        row.setBaseUrl("http://127.0.0.1:1"); // 必然连不上，但装配不依赖网络
        row.setChatEnabled(false);            // 只关掉「助手」
        rows.put("u-feat", row);

        LlmClient client = new LlmClient(new LlmConfigStore(props(true, "ollama"),
                new ObjectMapper(), statefulUserMapper(rows), cipher()));
        loginAs("u-feat");

        assertTrue(client.isAvailable(), "总开关开着、参数合法，应可装配");
        assertNull(client.chat(AiFeature.CHAT, "系统", "你好"),
                "被单独关闭的功能应降级返回 null");
    }

    // ------------------------------------------------------------------ 失败记录上界（P2-12）

    private static void loginAs(String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    /** 按内存 Map 模拟 user_llm_config 的可写替身 */
    private static UserLlmConfigMapper statefulUserMapper(Map<String, UserLlmConfig> rows) {
        UserLlmConfigMapper mapper = org.mockito.Mockito.mock(UserLlmConfigMapper.class);
        org.mockito.Mockito.when(mapper.selectById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    // 显式 Object：String.valueOf 有 char[] 等重载，泛型推断会挑错
                    Object id = inv.getArgument(0);
                    return rows.get(String.valueOf(id));
                });
        org.mockito.Mockito.when(mapper.insert(org.mockito.ArgumentMatchers.any(UserLlmConfig.class)))
                .thenAnswer(inv -> {
                    UserLlmConfig r = inv.getArgument(0);
                    rows.put(r.getUserId(), r);
                    return 1;
                });
        org.mockito.Mockito.when(mapper.updateById(org.mockito.ArgumentMatchers.any(UserLlmConfig.class)))
                .thenAnswer(inv -> {
                    UserLlmConfig r = inv.getArgument(0);
                    rows.put(r.getUserId(), r);
                    return 1;
                });
        return mapper;
    }

    /**
     * P2-12：装配失败的记录必须按<b>用户</b>收敛，不随配置保存次数增长。
     *
     * <p>原先用 {@code Set<"userId|版本">}，同一个用户每保存一次坏配置就留一个永不读取的 key，
     * 是只增不减的慢泄漏。改成 {@code Map<userId, version>} 后，容量上界 = 用户数。</p>
     */
    @Test
    void failedKeysBoundedByUserNotByConfigVersion() {
        Map<String, UserLlmConfig> rows = new ConcurrentHashMap<>();
        LlmConfigStore store = new LlmConfigStore(props(true, "ollama"),
                new ObjectMapper(), statefulUserMapper(rows), cipher());
        LlmClient client = new LlmClient(store);

        // 同一用户连续保存 10 次「provider 非法」的配置：每次都装配失败并记一次
        for (int i = 0; i < 10; i++) {
            store.saveFor("u-leak", new LlmConfig(true, "azure-openai-typo", "", "", "", null, 0));
            loginAs("u-leak");
            assertFalse(client.isAvailable(), "provider 非法应判为不可用");
        }
        // 另一个用户也失败一次
        store.saveFor("u-other", new LlmConfig(true, "azure-openai-typo", "", "", "", null, 0));
        loginAs("u-other");
        assertFalse(client.isAvailable());

        Object failed = ReflectionTestUtils.getField(client, "failedKeys");
        assertNotNull(failed);
        assertEquals(2, ((Map<?, ?>) failed).size(),
                "失败记录应为 2 条（两个用户各一条），而不是 11 条");
    }
}
