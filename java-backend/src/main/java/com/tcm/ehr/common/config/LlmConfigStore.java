package com.tcm.ehr.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 运行时配置存储（UX-68）。
 *
 * <p>启动时以 {@link LlmProperties}（{@code application.yml} 的 {@code llm} 段）为基线；
 * 之后可由 {@code PUT /api/llm/config} 覆盖。<b>覆盖只存在于内存</b>——不写回配置文件、
 * 也不落盘，因此 api-key 不会以明文形式新增到磁盘上；需要长期生效请写入 {@code application.yml}。</p>
 *
 * <p>{@link #version()} 用于让 {@link com.tcm.ehr.common.utils.LlmClient} 感知配置变更：
 * 版本号变化即丢弃已装配的 {@code ChatClient} 并按新参数重建，实现「保存即生效、无需重启」。</p>
 */
@Slf4j
@Component
public class LlmConfigStore {

    private final LlmProperties props;

    private volatile LlmConfig current;

    private final AtomicLong version = new AtomicLong();

    public LlmConfigStore(LlmProperties props) {
        this.props = props;
        this.current = LlmConfig.from(props);
    }

    /** 当前生效配置 */
    public LlmConfig get() {
        return current;
    }

    /** 配置版本号；每次覆盖自增 */
    public long version() {
        return version.get();
    }

    /** 覆盖运行时配置，返回生效后的配置 */
    public LlmConfig update(LlmConfig next) {
        this.current = next;
        long v = version.incrementAndGet();
        log.info("[LLM] 运行时配置已更新(v{})：enabled={}，provider={}，model={}",
                v, next.enabled(), next.provider(), next.model());
        return current;
    }
}
