package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link VersionedCache} 的两种用法（批次14 审核新增两段式接口后补的验收）。
 *
 * <p>为什么要测：这个类现在有两个入口 —— 版本比对的 {@code get}（loader 在锁内），
 * 和「加载在锁外」的两段式 {@code getIfPresent} / {@code put}。
 * 两段式那条路径没有版本串，和 {@code get} 混用时不能 NPE、也不能误命中。</p>
 */
class VersionedCacheTest {

    @Test
    @DisplayName("版本一致复用，版本变了才重新装载")
    void versionedGetReusesUntilVersionChanges() {
        VersionedCache<String> cache = new VersionedCache<>(8);
        AtomicInteger loads = new AtomicInteger();

        assertEquals("v1", cache.get("k", "1", () -> {
            loads.incrementAndGet();
            return "v1";
        }));
        assertEquals("v1", cache.get("k", "1", () -> {
            loads.incrementAndGet();
            return "v1";
        }));
        assertEquals(1, loads.get(), "版本没变就该复用，不该再装载");

        assertEquals("v2", cache.get("k", "2", () -> {
            loads.incrementAndGet();
            return "v2";
        }));
        assertEquals(2, loads.get());
    }

    @Test
    @DisplayName("两段式：getIfPresent 自己绝不回源，put 之后才命中")
    void twoPhaseDoesNotLoadOnItsOwn() {
        VersionedCache<String> cache = new VersionedCache<>(8);
        assertNull(cache.getIfPresent("k"));
        cache.put("k", "值");
        assertEquals("值", cache.getIfPresent("k"));
    }

    @Test
    @DisplayName("两段式写入的条目被 get 读到时不抛 NPE，按「版本不匹配」重新装载")
    void twoPhaseEntryIsToleratedByVersionedGet() {
        VersionedCache<String> cache = new VersionedCache<>(8);
        cache.put("k", "两段式写入");
        AtomicInteger loads = new AtomicInteger();

        assertEquals("重装", cache.get("k", "1", () -> {
            loads.incrementAndGet();
            return "重装";
        }));
        assertEquals(1, loads.get());
        // get 装载后该键就被版本化了
        assertEquals("重装", cache.get("k", "1", () -> {
            loads.incrementAndGet();
            return "重装";
        }));
        assertEquals(1, loads.get());
    }

    @Test
    @DisplayName("超出上限逐出最久未用的一项；getIfPresent 也会刷新新鲜度")
    void evictsLeastRecentlyUsed() {
        VersionedCache<String> cache = new VersionedCache<>(2);
        cache.put("a", "A");
        cache.put("b", "B");
        // 访问 a，让 b 成为「最久没用」
        assertEquals("A", cache.getIfPresent("a"));
        cache.put("c", "C");

        assertNull(cache.getIfPresent("b"), "b 最久未用，应被逐出");
        assertEquals("A", cache.getIfPresent("a"));
        assertEquals("C", cache.getIfPresent("c"));
    }

    @Test
    @DisplayName("invalidate / clear 是显式失效口径")
    void invalidateAndClear() {
        VersionedCache<String> cache = new VersionedCache<>(8);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.invalidate("a");
        assertNull(cache.getIfPresent("a"));
        assertEquals("B", cache.getIfPresent("b"));
        cache.clear();
        assertNull(cache.getIfPresent("b"));
    }

    @Test
    @DisplayName("上限至少为 1（传 0 或负数不会让缓存变成永久空）")
    void maxSizeIsAtLeastOne() {
        VersionedCache<String> cache = new VersionedCache<>(0);
        cache.put("a", "A");
        assertEquals("A", cache.getIfPresent("a"));
    }

    @Test
    @DisplayName("逐出在 get 路径同样生效（装载超额也要守住上限）")
    void evictionAlsoAppliesOnLoadPath() {
        VersionedCache<String> cache = new VersionedCache<>(2);
        cache.get("a", "1", () -> "A");
        cache.get("b", "1", () -> "B");
        cache.get("c", "1", () -> "C");
        assertNull(cache.getIfPresent("a"), "a 最久未用，应被逐出");
        assertEquals("C", cache.getIfPresent("c"));
    }
}
