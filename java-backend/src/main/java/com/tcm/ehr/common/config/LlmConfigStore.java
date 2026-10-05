package com.tcm.ehr.common.config;

import com.tcm.ehr.common.utils.AtomicJsonWriter;
import com.tcm.ehr.common.utils.LlmSecretCipher;
import com.tcm.ehr.common.utils.VersionedCache;
import com.tcm.ehr.domain.po.UserLlmConfig;
import com.tcm.ehr.mapper.UserLlmConfigMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 配置存储：<b>系统基线</b> + <b>每人一份私有配置</b>。
 *
 * <p><b>基线</b>：启动时以 {@link LlmProperties}（{@code application.yml} 的 {@code llm} 段）为准，
 * 再用旧版遗留的 {@code data/llm-config.json}（若存在）覆盖非密钥字段 —— 只读，不再回写。</p>
 *
 * <p><b>私有配置</b>：{@code user_llm_config} 每人一行，密钥经
 * {@link LlmSecretCipher} 以 AES-256-GCM 加密落库；用户没配则回落到基线。</p>
 *
 * <p>⚠️ <b>保存个人配置绝不回写全局文件</b>：那等于把一个人的密钥变成所有人的默认值。</p>
 *
 * <p>版本机制：{@link #versionFor(String)} 供 {@code LlmClient} 判断是否需要重建客户端，
 * 版本变化即丢弃已装配的 {@code ChatClient}，实现「保存即生效、无需重启」。</p>
 */
@Slf4j
@Component
public class LlmConfigStore {

    private final LlmProperties props;
    private final ObjectMapper mapper;
    /** 用户私有配置（每人一行，含加密密钥） */
    private final UserLlmConfigMapper userMapper;
    /** 密钥加解密 */
    private final LlmSecretCipher cipher;

    /**
     * 「配置 + 版本」一次性发布。
     *
     * 用一条 volatile 引用把两者绑在一起：读者要么看到「旧配置 + 旧版本」，
     * 要么看到「新配置 + 新版本」，不存在混搭的中间态。
     * 原实现是两次独立的 volatile 写（先换 current 再自增 version），中间那一瞬读到的组合是错的 ——
     * 计划里记的改法是「把两句调个个儿」，但那只是把窗口换个方向：版本先变、值后变时，
     * 读者会按新版本重建出基于旧值的对象并被缓存住，反而更难自愈。
     */
    private record Snapshot(LlmConfig config, long version) {
    }

    private volatile Snapshot snapshot;

    /** 单用户解析结果的缓存上限：每次 LLM 调用都会解析，命中 DB 没必要，但也不能无限长 */
    private static final int CACHE_MAX = 200;
    /** 版本没变就复用上次解析好的合并结果（省掉逐字段合并） */
    private final VersionedCache<LlmConfig> perUser = new VersionedCache<>(CACHE_MAX);

    public LlmConfigStore(LlmProperties props, ObjectMapper mapper,
                          UserLlmConfigMapper userMapper, LlmSecretCipher cipher) {
        this.props = props;
        this.mapper = mapper;
        this.userMapper = userMapper;
        this.cipher = cipher;
        this.snapshot = new Snapshot(load(props), 0);
    }

    /**
     * 取<b>某个用户</b>生效的配置：用户行覆盖基线，用户没配则用基线。
     *
     * <p>没有用户上下文（后台线程 / 启动期）时返回基线 —— 那时无法判断「谁的配置」。</p>
     */
    public LlmConfig getFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return get();
        }
        UserLlmConfig row = userMapper.selectById(userId);
        return perUser.get(userId, versionOf(row), () -> merge(row));
    }

    /**
     * 保存<b>某个用户</b>的配置（密钥加密落库）。
     *
     * <p>⚠️ 这里<b>不写全局配置文件</b>：配置是「每个用户一份」，把某人的配置写进全局基线
     * 等于让 A 的密钥变成所有人的默认值。</p>
     *
     * @param userId 用户 ID
     * @param cfg    已合并好的完整配置
     */
    public void saveFor(String userId, LlmConfig cfg) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("缺少用户上下文，无法保存 LLM 配置");
        }
        UserLlmConfig row = new UserLlmConfig();
        row.setUserId(userId);
        row.setEnabled(cfg.enabled());
        row.setProvider(cfg.provider());
        row.setBaseUrl(cfg.baseUrl());
        row.setModel(cfg.model());
        row.setTemperature(cfg.temperature());
        row.setTimeout(cfg.timeout());
        boolean newKey = cfg.apiKey() != null && !cfg.apiKey().isBlank();
        if (newKey) {
            row.setApiKey(cipher.encrypt(cfg.apiKey()));
        }
        UserLlmConfig existing = userMapper.selectById(userId);
        if (existing == null) {
            userMapper.insert(row);
        } else {
            if (!newKey && existing.getApiKey() != null) {
                // 显式回填原密文：apiKey 传空 = 「不修改」。
                // 刻意不依赖 MyBatis-Plus 对 null 字段「跳过」的默认策略 —— 那是隐式契约，
                // 一旦字段策略调整或换成别的写法，用户的密钥就会被静默清空。
                row.setApiKey(existing.getApiKey());
            }
            userMapper.updateById(row);
        }
        perUser.invalidate(userId);
        // 用户行变了，基线版本也要推进：versionFor 把两者拼在一起，不推进的话
        // 同秒内的两次保存会得到同一个版本串，缓存与 LlmClient 都不会重建
        Snapshot prev = snapshot;
        snapshot = new Snapshot(prev.config(), prev.version() + 1);
    }

    /**
     * 某个用户的配置版本串（基线版本 + 用户行指纹），供 LlmClient 判断是否重建客户端。
     */
    public String versionFor(String userId) {
        if (userId == null || userId.isBlank()) {
            return String.valueOf(version());
        }
        return versionOf(userMapper.selectById(userId));
    }

    /**
     * 用户行的版本指纹。
     *
     * <p><b>基线版本必须参与</b>：用户没有自有配置时生效的是基线，基线一变该用户的生效
     * 配置就跟着变。若版本串里只有用户行、没有基线版本，无自有配置的用户会一直命中
     * 旧缓存，表现为「改了却不生效」—— 且很难一眼看出。</p>
     *
     * <p>只用 {@code update_time} 也不够：它精度到秒，同一秒内改两次会漏判成「没变」。
     * 故并入各字段值 + 密文长度兜底。</p>
     */
    private String versionOf(UserLlmConfig row) {
        long v = version();
        if (row == null) {
            return v + ":none";
        }
        return v + ":"
                + (row.getUpdateTime() == null ? "" : row.getUpdateTime().toString())
                + "|" + row.getEnabled() + "|" + row.getProvider() + "|" + row.getBaseUrl()
                + "|" + row.getModel() + "|" + row.getTemperature() + "|" + row.getTimeout()
                + "|" + (row.getApiKey() == null ? 0 : row.getApiKey().length());
    }

    /** 用户行覆盖基线；用户没配就返回基线本身 */
    private LlmConfig merge(UserLlmConfig row) {
        if (row == null) {
            return get();
        }
        LlmConfig base = get();
        return new LlmConfig(
                row.getEnabled() != null && row.getEnabled(),
                row.getProvider() != null ? row.getProvider() : base.provider(),
                row.getBaseUrl() != null ? row.getBaseUrl() : base.baseUrl(),
                cipher.decrypt(row.getApiKey()),
                row.getModel() != null ? row.getModel() : base.model(),
                row.getTemperature() != null ? row.getTemperature() : base.temperature(),
                row.getTimeout() != null ? row.getTimeout() : base.timeout());
    }

    /** 当前生效配置 */
    public LlmConfig get() {
        return snapshot.config();
    }

    /** 配置版本号；每次覆盖自增 */
    public long version() {
        return snapshot.version();
    }

    /** 覆盖运行时配置：更新内存、推进版本、并把非密钥字段落盘 */
    public LlmConfig update(LlmConfig next) {
        // 1. 配置与版本一次性发布（见 Snapshot 的注释：分两步写会露出混搭的中间态）
        Snapshot prev = snapshot;
        long v = prev.version() + 1;
        snapshot = new Snapshot(next, v);
        // 2. 非密钥字段落盘（api-key 只留在内存）
        persist(next);
        log.info("[LLM] 运行时配置已更新(v{})：enabled={}，provider={}，model={}（非密钥字段已落盘）",
                v, next.enabled(), next.provider(), next.model());
        return get();
    }

    // ------------------------------------------------------------------ 内部

    /** 基线（yml）叠加已落盘的非密钥字段 */
    private LlmConfig load(LlmProperties p) {
        LlmConfig base = LlmConfig.from(p);
        Map<String, Object> m = readFile(p.getConfigFile());
        // 1. 没有落盘文件就纯用 yml 基线
        if (m == null) {
            return base;
        }
        // 2. 逐字段覆盖；类型不符或缺失时回落到基线值
        // 3. apiKey 恒取基线（yml）——落盘文件里没有密钥，也不该有
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
            // 1. 文件不存在是正常状态（还没保存过），不是错误
            if (!Files.exists(f)) {
                return null;
            }
            return mapper.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 2. 坏了就用 yml 基线继续跑，不让配置问题拦住服务启动
            log.warn("[LLM] 读取运行时配置失败（用 yml 基线）: {}", e.getMessage());
            return null;
        }
    }

    /** 落盘非密钥字段（api-key 绝不写入） */
    private void persist(LlmConfig c) {
        // 1. 只列非密钥字段——这个 map 是落盘内容的唯一来源，别顺手把密钥加进来
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("provider", c.provider());
        m.put("baseUrl", c.baseUrl());
        m.put("model", c.model());
        m.put("temperature", c.temperature());
        m.put("timeout", c.timeout());
        try {
            // 2. 原子写：先写临时文件再 move，避免半截 JSON 让下次启动静默回退默认
            AtomicJsonWriter.write(mapper, Paths.get(props.getConfigFile()), m);
        } catch (Exception e) {
            log.warn("[LLM] 运行时配置落盘失败（本次仅在内存生效）: {}", e.getMessage());
        }
    }
}
