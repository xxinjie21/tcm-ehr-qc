package com.tcm.ehr.common.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量运行期指标（Redis 9.1 / P1-14）。
 *
 * <p><b>为什么不用 Actuator + Micrometer</b>：本项目按<b>离线优先</b>构建（{@code mvn -o}），
 * 本地仓库没有与 Spring Boot 4.1.1 对齐的 actuator 制品；且 actuator 端点默认<b>不鉴权</b>，
 * 在医疗系统上直接暴露 {@code /actuator/metrics} 是信息泄露面。这里用进程内累加器实现同样几项
 * 指标，经<b>管理员专属</b>的 {@code GET /api/metrics} 读出 —— 口径不变，依赖与暴露面都不增加。</p>
 *
 * <p><b>指标清单</b>：缓存命中/未命中/绕过/异常、AI 任务耗时分布、LLM 客户端缓存命中率、
 * 令牌版本降级放行次数。全部是「累加」语义，进程重启即归零（诊断用，不做长期存储）。</p>
 *
 * <p>刻意用<b>静态</b>累加器：这些埋点散在 {@code StatsCache} / {@code AsyncTaskRegistry} /
 * {@code JwtUtil} 等已被大量单测直接 {@code new} 出来的类里，走注入会连锁改动它们的构造签名；
 * 静态计数器对调用方零侵入。</p>
 */
public final class Metrics {

    private static final ConcurrentMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Timer> TIMERS = new ConcurrentHashMap<>();

    /** 耗时直方图分桶上界（毫秒）；最后一桶表示「≥ 最大上界」 */
    private static final long[] BUCKET_BOUNDS = {50, 100, 200, 500, 1000, 3000, 10000, 30000};

    private Metrics() {
    }

    /** 计数 +1 */
    public static void inc(String name) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    /** 记录一次耗时（毫秒） */
    public static void observeMs(String name, long ms) {
        TIMERS.computeIfAbsent(name, k -> new Timer()).record(ms);
    }

    /**
     * 取全部指标的只读快照。
     *
     * @return {@code {counters: {名: 值}, timers: {名: {count,sumMs,maxMs,buckets:{上界: 计数}}}}}
     */
    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> counters = new TreeMap<>();
        COUNTERS.forEach((k, v) -> counters.put(k, v.sum()));
        out.put("counters", counters);
        Map<String, Object> timers = new TreeMap<>();
        TIMERS.forEach((k, v) -> timers.put(k, v.snapshot()));
        out.put("timers", timers);
        return out;
    }

    /** 清空全部指标（测试用） */
    public static void reset() {
        COUNTERS.clear();
        TIMERS.clear();
    }

    /** 计数/求和/最大值/直方图，全部无锁累加 */
    private static final class Timer {
        private final LongAdder count = new LongAdder();
        private final LongAdder sumMs = new LongAdder();
        private final AtomicLong maxMs = new AtomicLong();
        private final AtomicLongArray buckets = new AtomicLongArray(BUCKET_BOUNDS.length + 1);

        void record(long ms) {
            count.increment();
            sumMs.add(ms);
            maxMs.accumulateAndGet(ms, Math::max);
            int idx = BUCKET_BOUNDS.length;
            for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
                if (ms <= BUCKET_BOUNDS[i]) {
                    idx = i;
                    break;
                }
            }
            buckets.incrementAndGet(idx);
        }

        Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count.sum());
            m.put("sumMs", sumMs.sum());
            m.put("maxMs", maxMs.get());
            Map<String, Long> hist = new LinkedHashMap<>();
            for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
                hist.put("<=" + BUCKET_BOUNDS[i], buckets.get(i));
            }
            hist.put(">" + BUCKET_BOUNDS[BUCKET_BOUNDS.length - 1], buckets.get(BUCKET_BOUNDS.length));
            m.put("buckets", hist);
            return m;
        }
    }
}
