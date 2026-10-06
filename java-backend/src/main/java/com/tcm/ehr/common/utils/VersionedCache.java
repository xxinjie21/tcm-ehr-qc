package com.tcm.ehr.common.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 按「版本串」复用的有界缓存：版本一致就复用已解析好的值，不一致才重新装载。
 *
 * 为什么要抽成公共类：配置侧有三个 store（LLM 配置 / 质控规则 / 词典）都要做同一件事 ——
 * 回源读一行 DB、拿到它的版本、版本没变就复用上次的解析结果（省掉 JSON 解析或客户端重建）。
 * 各写一份的代价是三套失效口径；而「配置类不得以进程内单例为权威，一律 DB 为准 + 版本号失效」
 * 这条跨批硬约束正是靠「每次都回源比对版本」守住的，口径不能各写各的。
 *
 * 线程安全：get / getIfPresent / put / invalidate 都加锁。LinkedHashMap 的访问序在并发下会坏掉
 * （并发 put 可能丢项），而缓存只是优化、不是权威 —— 拿不到锁也只是慢一点，不会读到错的配置。
 * 「加载在锁外」的两段式用法见 {@link #getIfPresent}。
 *
 * @param <V> 缓存值类型
 */
public final class VersionedCache<V> {

    private record Entry<V>(V value, String version) {
    }

    private final int maxSize;
    /** accessOrder = true：最近用过的排在后面，队首即「最久没用」 */
    private final Map<String, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);

    public VersionedCache(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    /**
     * 版本一致则复用，否则用 loader 重算并记住新版本。
     *
     * <p><b>loader 在锁内执行</b>：适合「回源读一行 DB + 解析 JSON」这种微秒级的装载。
     * 若装载是一次网络往返（如 ES 检索），请改用 {@link #getIfPresent} / {@link #put}
     * 自己拼两段式 —— 否则所有并发请求会在锁上排队，等于把并发检索串行化。</p>
     *
     * @param key     缓存键（userId / orgId / orgId+type）
     * @param version 本次从 DB 读到的版本串；调用方保证「版本变了 = 值该重算」
     * @param loader  重算逻辑
     * @return 生效的值（可能来自缓存）
     */
    public synchronized V get(String key, String version, Supplier<V> loader) {
        Entry<V> hit = map.get(key);
        // 用 Objects.equals：两段式 put 存进来的条目没有版本串（null），
        // 直接调 hit.version().equals(version) 会 NPE
        if (hit != null && Objects.equals(hit.version(), version)) {
            return hit.value();
        }
        V value = loader.get();
        putEntry(key, value, version);
        return value;
    }

    /**
     * 只读缓存、不回源：没有这个键（或版本对不上）就返回 null。
     *
     * <p>给「装载必须放在锁外」的调用方用（典型是 ES 检索这类网络往返）。两段式写法：
     * <pre>
     *   V v = cache.getIfPresent(key);
     *   if (v == null) { v = loadOutsideLock(); cache.put(key, v); }
     * </pre>
     * 并发下同一键可能被装载两次（两个线程都没命中），这是**有意的取舍**：宁可偶发多查一次，
     * 也不把慢装载放进锁里。缓存只是优化、不是权威，重复装载不会读到错的值。</p>
     *
     * @param key 缓存键
     * @return 命中的值；未命中返回 null
     */
    public synchronized V getIfPresent(String key) {
        Entry<V> hit = map.get(key);
        return hit == null ? null : hit.value();
    }

    /**
     * 只写缓存、不做版本比对；与 {@link #getIfPresent} 配套用于「装载在锁外」的两段式。
     *
     * <p>写入的条目不带版本串，因此**只应被 {@link #getIfPresent} 读取**。
     * 失效靠调用方显式调 {@link #invalidate} / {@link #clear}。</p>
     *
     * @param key   缓存键
     * @param value 要缓存的值（不应为 null；null 在读取侧表示「未命中」）
     */
    public synchronized void put(String key, V value) {
        putEntry(key, value, null);
    }

    /** put 与 get 的公共写入路径：写入 + 逐出超额项 */
    private void putEntry(String key, V value, String version) {
        map.put(key, new Entry<>(value, version));
        // 手动淘汰最久未用的一项：LinkedHashMap 只有覆写 removeEldestEntry 才会自动淘汰
        while (map.size() > maxSize) {
            Iterator<Map.Entry<String, Entry<V>>> it = map.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    /**
     * 主动失效某个键。
     *
     * 写路径改完值之后必须调它：版本串若取的是 DB 时间戳，同秒内的两次改动会得到同一个版本，
     * 光靠比对版本会复用旧值。
     */
    public synchronized void invalidate(String key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }
}
