package com.tcm.ehr.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 运行时配置存储。
 *
 * <p>启动时以 {@link LlmProperties}（{@code application.yml} 的 {@code llm} 段）为基线，
 * 再用 {@code data/llm-config.json}（若存在）覆盖其中的<b>非密钥字段</b>；
 * 之后可由 {@code PUT /api/llm/config} 覆盖。</p>
 *
 * <p><b>持久化边界</b>：只落盘 enabled / provider / baseUrl / model / temperature / timeout，
 * <b>api-key 永不落盘</b>，故重启后 api-key 为空、需重新填写（界面会回显"已配置 sk-****abcd"
 * 仅限当前进程内设置过的密钥）。这样既解决"每次打开都显示未启用、配置为空"，又不把密钥写到磁盘。</p>
 *
 * <p>{@link #version()} 用于让 {@link com.tcm.ehr.common.utils.LlmClient} 感知配置变更：
 * 版本号变化即丢弃已装配的 {@code ChatClient} 并按新参数重建，实现「保存即生效、无需重启」。</p>
 */
@Slf4j
@Component
public class LlmConfigStore {

    private final LlmProperties props;
    private final ObjectMapper mapper;

    private volatile LlmConfig current;

    private final AtomicLong version = new AtomicLong();

    public LlmConfigStore(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.current = load(props);
    }

    /** 当前生效配置 */
    public LlmConfig get() {
        return current;
    }

    /** 配置版本号；每次覆盖自增 */
    public long version() {
        return version.get();
    }

    /** 覆盖运行时配置：更新内存、自增版本、并把非密钥字段落盘 */
    public LlmConfig update(LlmConfig next) {
        this.current = next;
        long v = version.incrementAndGet();
        persist(next);
        log.info("[LLM] 运行时配置已更新(v{})：enabled={}，provider={}，model={}（非密钥字段已落盘）",
                v, next.enabled(), next.provider(), next.model());
        return current;
    }

    // ------------------------------------------------------------------ 内部

    /** 基线（yml）叠加已落盘的非密钥字段 */
    private LlmConfig load(LlmProperties p) {
        LlmConfig base = LlmConfig.from(p);
        Map<String, Object> m = readFile(p.getConfigFile());
        if (m == null) {
            return base;
        }
        return new LlmConfig(
                m.get("enabled") instanceof Boolean b ? b : base.enabled(),
                m.get("provider") != null ? String.valueOf(m.get("provider")) : base.provider(),
                m.get("baseUrl") != null ? String.valueOf(m.get("baseUrl")) : base.baseUrl(),
                base.apiKey(),
                m.get("model") != null ? String.valueOf(m.get("model")) : base.model(),
                m.get("temperature") instanceof Number n ? n.doubleValue() : base.temperature(),
                m.get("timeout") instanceof Number n ? n.intValue() : base.timeout());
    }

    private Map<String, Object> readFile(String path) {
        try {
            Path f = Paths.get(path);
            if (!Files.exists(f)) {
                return null;
            }
            return mapper.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[LLM] 读取运行时配置失败（用 yml 基线）: {}", e.getMessage());
            return null;
        }
    }

    /** 落盘非密钥字段（api-key 绝不写入） */
    private void persist(LlmConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("provider", c.provider());
        m.put("baseUrl", c.baseUrl());
        m.put("model", c.model());
        m.put("temperature", c.temperature());
        m.put("timeout", c.timeout());
        try {
            Path f = Paths.get(props.getConfigFile());
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), m);
        } catch (Exception e) {
            log.warn("[LLM] 运行时配置落盘失败（本次仅在内存生效）: {}", e.getMessage());
        }
    }
}
