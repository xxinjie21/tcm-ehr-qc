package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.LlmConfig;
import com.tcm.ehr.common.config.LlmConfigStore;
import com.tcm.ehr.common.config.LlmProperties;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClient 降级契约测试（纯对象构造，不加载 Spring 容器、不联网、不需要任何 api-key）。
 *
 * <p>锁定三条硬约束：<b>配置缺失/非法只降级不抛异常</b>（不阻塞主流程）、
 * <b>llm.enabled=false 时完全不装配模型</b>，以及 <b>运行时覆盖立即生效</b>（UX-68，无需重启）。
 * 前两条一旦被破坏，要么主流程被 LLM 拖死，要么重演「缺 api-key 导致整个上下文启动失败」。</p>
 */
class LlmClientTest {

    /**
     * 每个用例一个**唯一且尚不存在**的配置文件路径。
     *
     * <p>必须隔离：{@link LlmConfigStore} 构造时会读 {@code llm.configFile} 并用其中的非密钥字段
     * 覆盖 yml 基线，而 {@code update()} 又会把配置<b>写回</b>该文件。默认路径是
     * {@code data/llm-config.json} —— 这是开发机上的真实运行时配置（且被 .gitignore 忽略），
     * 用它会让断言变成「看本机文件内容」，跑完还会污染用户的配置。</p>
     *
     * <p>目录用 {@link TempDir}（每次运行新建、跑完删除）而不是固定的 {@code target/} 子目录：
     * 只要路径在同一台机器上跨运行可复用，后一次运行就会读到前一次 {@code update()} 写下的
     * {@code enabled=true}，断言随之翻车 —— 这个顺序依赖踩过一次。</p>
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

    private static LlmClient clientOf(LlmProperties p) {
        return new LlmClient(new LlmConfigStore(p, new ObjectMapper()));
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

    // ------------------------------------------------------------------ UX-68 运行时覆盖

    /** 基线关闭 → 运行时覆盖为开启：立即生效，无需重启 */
    @Test
    void runtimeOverride_takesEffectWithoutRestart() {
        LlmConfigStore store = new LlmConfigStore(props(false, "ollama"), new ObjectMapper());
        LlmClient client = new LlmClient(store);

        assertFalse(client.isAvailable(), "覆盖前应为关闭态");
        assertFalse(client.isEnabled(), "覆盖前 isEnabled 应为 false");

        store.update(new LlmConfig(true, "ollama", "", "", "", 0.2D, 60000));

        assertTrue(client.isEnabled(), "覆盖后 isEnabled 应为 true");
        assertTrue(client.isAvailable(), "运行时覆盖后应重新装配并可用，无需重启");
    }

    /** 覆盖为非法参数：仍只降级，不得抛（否则保存动作会把接口打 500） */
    @Test
    void runtimeOverrideWithIllegalProvider_shouldDegradeInsteadOfThrowing() {
        LlmConfigStore store = new LlmConfigStore(props(false, "ollama"), new ObjectMapper());
        LlmClient client = new LlmClient(store);

        store.update(new LlmConfig(true, "not-a-provider", "", "", "", null, 0));

        assertDoesNotThrow(client::isAvailable, "覆盖成非法 provider 不应抛异常");
        assertFalse(client.isAvailable(), "非法 provider 应判为不可用");
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
}
