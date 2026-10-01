package com.tcm.ehr.common.utils;

import com.tcm.ehr.mapper.DbLockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨实例互斥的契约（批次 16）。
 *
 * <p>最要紧的一条是<b>拿不到锁不放行</b>：写成「拿不到就继续」等于没有锁，
 * 而且只在高并发时发作 —— 这种 bug 现场几乎无法复现。</p>
 */
class DistLockTest {

    @Test
    @DisplayName("拿到锁：临界区执行且返回其结果")
    void runsBodyWhenLockAcquired() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(1);
        when(mapper.release(anyString())).thenReturn(1);

        String out = new DistLock(mapper).runLocked("k", () -> "done");

        assertEquals("done", out);
        verify(mapper).release("k");
    }

    @Test
    @DisplayName("拿不到锁：抛异常，且临界区绝不执行（核心红线）")
    void refusesWhenLockNotAcquired() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(0);
        AtomicInteger entered = new AtomicInteger();

        assertThrows(IllegalStateException.class,
                () -> new DistLock(mapper).runLocked("k", () -> {
                    entered.incrementAndGet();
                    return "x";
                }));

        assertEquals(0, entered.get(), "没拿到锁却进入了临界区 = 等于没有锁");
        verify(mapper, never()).release(anyString());
    }

    @Test
    @DisplayName("取锁返回 null（DB 出错）同样拒绝，不当成功处理")
    void nullResultIsAlsoRefused() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> new DistLock(mapper).runLocked("k", () -> "x"));
    }

    @Test
    @DisplayName("临界区抛异常：仍然释放锁（否则一次异常堵死后续所有请求）")
    void releasesLockOnException() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(1);
        when(mapper.release(anyString())).thenReturn(1);

        assertThrows(RuntimeException.class,
                () -> new DistLock(mapper).runLocked("k", () -> {
                    throw new RuntimeException("boom");
                }));

        verify(mapper).release("k");
    }

    @Test
    @DisplayName("释放锁本身失败：不影响临界区结果（只记日志）")
    void releaseFailureDoesNotMaskResult() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(1);
        when(mapper.release(anyString())).thenThrow(new RuntimeException("redis-like down"));

        assertEquals("ok", new DistLock(mapper).runLocked("k", () -> "ok"));
    }

    @Test
    @DisplayName("锁名按「类型+组织」划分：不同组织互不阻塞")
    void lockNameIsPartitionedByTypeAndOrg() {
        String a = DistLock.dictRebuildLock("herb", "org-A");
        String b = DistLock.dictRebuildLock("herb", "org-B");
        String c = DistLock.dictRebuildLock("pattern", "org-A");

        assertNotEquals(a, b, "不同组织必须是不同的锁");
        assertNotEquals(a, c, "不同类型必须是不同的锁");
        assertEquals(a, DistLock.dictRebuildLock("herb", "org-A"), "同名应稳定");
        assertTrue(a.contains("org-A") && a.contains("herb"), "锁名应含业务 key：" + a);
    }
}
