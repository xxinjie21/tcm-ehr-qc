package com.tcm.ehr.common.utils;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 按「版本串」复用的有界缓存：版本一致就复用已解析好的值，不一致才重新装载。
 *
 * 为什么要抽成公共类：配置侧有三个 store（LLM 配置 / 质控规则 / 词典）都要做同一件事 ——
 * 回源读一行 DB、拿到它的版本、版本没变就复用上次的解析结果（省掉 JSON 解析或客户端重建）。
 * 各写一份的代价是三套失效口径；而「配置类不得以进程内单例为权威，一律 DB 为准 + 版本号失效」
 * 这条跨批硬约束正是靠「每次都回源比对版本」守住的，口径不能各写各的。
 *
 * 线程安全：get / invalidate 都加锁。LinkedHashMap 的访问序在并发下会坏掉
 * （并发 put 可能丢项），而缓存只是优化、不是权威 —— 拿不到锁也只是慢一点，不会读到错的配置。
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
     * @param key     缓存键（userId / orgId / orgId+type）
     * @param version 本次从 DB 读到的版本串；调用方保证「版本变了 = 值该重算」
     * @param loader  重算逻辑
     * @return 生效的值（可能来自缓存）
     */
    public synchronized V get(String key, String version, Supplier<V> loader) {
        Entry<V> hit = map.get(key);
        if (hit != null && hit.version().equals(version)) {
            return hit.value();
        }
        V value = loader.get();
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
        return value;
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
