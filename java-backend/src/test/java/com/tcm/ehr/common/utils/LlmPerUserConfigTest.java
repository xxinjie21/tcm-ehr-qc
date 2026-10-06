package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.config.LlmConfig;
import com.tcm.ehr.common.config.LlmConfigStore;
import com.tcm.ehr.common.config.LlmProperties;
import com.tcm.ehr.domain.po.UserLlmConfig;
import com.tcm.ehr.mapper.UserLlmConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 配置「每人一份 + 密钥加密」的契约测试（批次 8a）。
 *
 * <p>锁三件事：①密钥不以明文落库；②A 的配置不会流到 B；③没有自有配置的用户跟随基线变化。</p>
 */
class LlmPerUserConfigTest {

    private static final String KEY_A =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String KEY_B =
            "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    /** 内存版 user_llm_config：用 mock 支撑，不连库，专心验证「取谁的值 / 存到哪」 */
    private Map<String, UserLlmConfig> rows;
    private Map<String, Integer> writes;

    private UserLlmConfigMapper newUserMapper() {
        rows = new ConcurrentHashMap<>();
        writes = new ConcurrentHashMap<>();
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
                    writes.merge(r.getUserId(), 1, Integer::sum);
                    rows.put(r.getUserId(), r);
                    return 1;
                });
        org.mockito.Mockito.when(mapper.updateById(org.mockito.ArgumentMatchers.any(UserLlmConfig.class)))
                .thenAnswer(inv -> {
                    UserLlmConfig r = inv.getArgument(0);
                    writes.merge(r.getUserId(), 1, Integer::sum);
                    rows.put(r.getUserId(), r);
                    return 1;
                });
        return mapper;
    }

    /**
     * 本测试专用的配置文件路径。
     *
     * <p>必须指向一个<b>专属且不存在</b>的文件：{@code LlmConfigStore} 构造时会读它并覆盖基线，
     * 而 {@code update()} 又会把它写出来。若多个测试共用同一个路径，前一个测试留下的文件
     * 会让后一个测试的「基线未开启」前提失效 —— 表现为「第一次跑过、第二次跑挂」。</p>
     */
    private static final String LLM_CONFIG_FILE = "target/no-such-llm-config-peruser-test.json";

    private UserLlmConfigMapper userMapper;
    private LlmSecretCipher cipher;
    private LlmConfigStore store;

    @BeforeEach
    void setUp() {
        // 清掉上一次跑残留的配置文件，保证「基线 = 测试设定的 props」这个前提
        java.io.File f = new java.io.File(LLM_CONFIG_FILE);
        if (f.exists() && !f.delete()) {
            throw new IllegalStateException("清理残留的 LLM 配置文件失败: " + LLM_CONFIG_FILE);
        }
        userMapper = newUserMapper();
        cipher = new LlmSecretCipher(KEY_A);
        LlmProperties p = new LlmProperties();
        p.setEnabled(false);
        p.setProvider("ollama");
        p.setConfigFile(LLM_CONFIG_FILE);
        store = new LlmConfigStore(p, new ObjectMapper(), userMapper, cipher);
    }

    private void loginAs(String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", userId);
        req.setAttribute("currentUsername", userId);
        req.setAttribute("currentRole", "用户");
        req.setAttribute("currentOrgId", "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    // ------------------------------------------------------------------ 加密

    /** 加密往返：密文里看不到明文，解出来一致 */
    @Test
    void secretIsEncryptedAndRoundTrips() {
        String plain = "sk-abcdefghijklmnop";
        String cipherText = cipher.encrypt(plain);

        assertNotNull(cipherText);
        assertFalse(cipherText.contains(plain), "密文不得包含明文：" + cipherText);
        assertEquals(plain, cipher.decrypt(cipherText));
    }

    /** 两次加密同一明文得到不同密文（每次新 IV），否则可比对出「哪些用户密钥相同」 */
    @Test
    void encryptionUsesFreshIvEachTime() {
        assertNotEquals(cipher.encrypt("same-key"), cipher.encrypt("same-key"));
    }

    /** 换密钥后旧密文解不开，按「未配置密钥」处理且不抛 —— 否则一次换密钥就让所有人登录失败 */
    @Test
    void wrongKeyDegradesToEmptyInsteadOfThrowing() {
        String cipherText = cipher.encrypt("sk-secret");
        LlmSecretCipher other = new LlmSecretCipher(KEY_B);

        assertEquals("", other.decrypt(cipherText));
    }

    /**
     * 未配置加密密钥时：不阻塞启动，但拒绝保存（绝不能明文落库）。
     *
     * 25.1：这类「服务未就绪」由 IllegalStateException 改为 ServiceNotReadyException
     * （HTTP 503 + code=1011），不再被兜底成 500；文案必须点名缺失的环境变量，
     * 否则使用者只知道「失败」却不知道要做什么。
     */
    @Test
    void withoutKeySavingSecretIsRejectedNotDowngradedToPlain() {
        LlmSecretCipher none = new LlmSecretCipher("");
        assertFalse(none.available());
        com.tcm.ehr.common.exception.ServiceNotReadyException e = org.junit.jupiter.api.Assertions.assertThrows(
                com.tcm.ehr.common.exception.ServiceNotReadyException.class,
                () -> none.encrypt("sk-secret"));
        assertTrue(e.getMessage().contains("TCM_LLM_ENC_KEY"), e.getMessage());
    }

    // ------------------------------------------------------------------ 每人一份

    /** A 的配置对 B 不可见；没配的用户回落到基线 */
    @Test
    void configsAreIsolatedPerUser() {
        loginAs("u-a");
        store.saveFor("u-a", new LlmConfig(true, "openai", "https://a.example/v1", "sk-a", "m-a", 0.1D, 30000));

        loginAs("u-b");
        LlmConfig b = store.getFor("u-b");
        assertFalse(b.enabled(), "B 不应继承 A 的 enabled");
        assertFalse("sk-a".equals(b.apiKey()), "B 不应拿到 A 的密钥");
        assertEquals("sk-a", store.getFor("u-a").apiKey(), "A 自己的密钥要能取回");
    }

    /** 保存个人配置绝不写全局基线文件 —— 否则 A 的密钥变成所有人的默认 */
    @Test
    void savingOneUserDoesNotTouchBaseline() {
        LlmConfig before = store.get();
        store.saveFor("u-x", new LlmConfig(true, "openai", "", "sk-x", "", 0.2D, 60000));

        assertEquals(before.enabled(), store.get().enabled(), "基线不应被个人保存改写");
    }

    /** 密钥留空 = 不修改：密文不能被覆盖成空 */
    @Test
    void emptyKeyKeepsExistingSecret() {
        store.saveFor("u-c", new LlmConfig(true, "openai", "", "sk-first", "", 0.2D, 60000));
        store.saveFor("u-c", new LlmConfig(true, "openai", "", "", "m2", 0.2D, 60000));

        assertEquals("sk-first", store.getFor("u-c").apiKey(), "空密钥不得覆盖已有密文");
        assertEquals("m2", store.getFor("u-c").model());
    }

    /** 基线改了，无自有配置的用户要跟着变 —— 版本串必须含基线版本，否则命中旧缓存 */
    @Test
    void baselineChangeIsVisibleToUsersWithoutOwnConfig() {
        loginAs("u-none");
        assertFalse(store.getFor("u-none").enabled(), "前置：基线未开启");

        store.update(new LlmConfig(true, "ollama", "", "", "", 0.2D, 60000));

        assertTrue(store.getFor("u-none").enabled(), "基线变化必须对无自有配置的用户生效");
    }
}
