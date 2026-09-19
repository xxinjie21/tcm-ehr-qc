package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClient 降级契约测试（纯对象构造，不加载 Spring 容器、不联网、不需要任何 api-key）。
 *
 * <p>锁定两条硬约束：<b>配置缺失/非法只降级不抛异常</b>（不阻塞主流程），
 * 以及 <b>llm.enabled=false 时完全不装配模型</b>。这两条一旦被破坏，
 * 要么主流程被 LLM 拖死，要么重演「缺 api-key 导致整个上下文启动失败」。</p>
 */
class LlmClientTest {

    private static LlmProperties props(boolean enabled, String provider) {
        LlmProperties p = new LlmProperties();
        p.setEnabled(enabled);
        p.setProvider(provider);
        return p;
    }

    /** 关闭（默认态）：不装配、不可用，调用返回 null 且不抛 */
    @Test
    void disabled_shouldNotAssembleAndDegradeQuietly() {
        LlmClient client = new LlmClient(props(false, "ollama"));

        assertFalse(client.isAvailable(), "llm.enabled=false 时不应装配模型");
        assertNull(client.chat("随便问一句"), "关闭时应降级返回 null");
        assertNull(client.chat("系统提示", "用户输入"), "关闭时应降级返回 null");
    }

    /** provider 写错：装配失败被收敛为降级，不得抛异常 */
    @Test
    void illegalProvider_shouldDegradeInsteadOfThrowing() {
        LlmClient client = new LlmClient(props(true, "azure-openai-typo"));

        assertDoesNotThrow(client::isAvailable, "provider 非法不应抛异常");
        assertFalse(client.isAvailable(), "provider 非法应判为不可用");
        assertNull(client.chat("你好"), "provider 非法应降级返回 null");
    }

    /** 选 openai 但没填 api-key：降级，绝不抛（这正是自动装配会把启动搞崩的场景） */
    @Test
    void openaiWithoutApiKey_shouldDegradeInsteadOfThrowing() {
        LlmProperties p = props(true, "openai");
        p.setApiKey("");
        LlmClient client = new LlmClient(p);

        assertDoesNotThrow(client::isAvailable, "缺 api-key 不应抛异常");
        assertFalse(client.isAvailable(), "缺 api-key 应判为不可用");
        assertNull(client.chat("你好"), "缺 api-key 应降级返回 null");
    }

    /** 本机 Ollama 通道无需凭据，应当能装配成功 */
    @Test
    void ollamaWithoutApiKey_shouldAssemble() {
        LlmClient client = new LlmClient(props(true, "ollama"));

        assertTrue(client.isAvailable(), "ollama 通道不需要 api-key，应装配成功");
        assertTrue("ollama".equals(client.provider()));
    }

    /** Ollama 服务未启动（指向必然连不上的端口）：调用期失败也必须是降级，不得抛 */
    @Test
    void ollamaUnreachable_shouldDegradeOnCall() {
        LlmProperties p = props(true, "ollama");
        p.setBaseUrl("http://127.0.0.1:1");
        p.setTimeout(1000);
        LlmClient client = new LlmClient(p);

        assertTrue(client.isAvailable(), "装配不依赖网络，应仍可用");
        assertDoesNotThrow(() -> assertNull(client.chat("你好"), "连不上 Ollama 应降级返回 null"));
    }
}
