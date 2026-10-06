package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.exception.ServiceNotReadyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨实例互斥的契约（批次 16 / 16.1 Redisson）。
 *
 * <p>最要紧的两条：拿不到锁<b>不放行</b>（写成「拿不到就继续」等于没有锁，且只在高并发时发作），
 * 以及锁服务本身故障时 <b>fail-closed</b>（同样不放行，返回 503 而不是默默跑临界区）。</p>
 */
class DistLockTest {

    /** 造一个「能拿到锁」的 Redisson 客户端 */
    private static RedissonClient redisson(boolean locked) {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = lock(locked, true);
        when(client.getLock(anyString())).thenReturn(lock);
        return client;
    }

    private static RLock lock(boolean locked, boolean held) {
        RLock lock = mock(RLock.class);
        try {
            when(lock.tryLock(anyLong(), any())).thenReturn(locked);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        when(lock.isHeldByCurrentThread()).thenReturn(held);
        return lock;
    }

    private static RLock lockOf(RedissonClient client) {
        return client.getLock("k");
    }

    @Test
    @DisplayName("拿到锁：临界区执行、返回其结果、结束即释放")
    void runsBodyWhenLockAcquired() {
        RedissonClient client = redisson(true);
        RLock lock = lockOf(client);

        String out = new DistLock(client).runLocked("k", () -> "done");

        assertEquals("done", out);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("拿不到锁：抛异常，且临界区绝不执行（核心红线）")
    void refusesWhenLockNotAcquired() {
        RedissonClient client = redisson(false);
        RLock lock = lockOf(client);
        AtomicInteger entered = new AtomicInteger();

        ConcurrentOperationException thrown = assertThrows(ConcurrentOperationException.class,
                () -> new DistLock(client).runLocked("k", () -> {
                    entered.incrementAndGet();
                    return "x";
                }));
        // 文案要能被前端直接展示（批次 25 工作项 1：不再落兜底 500 的「系统异常（追踪码 …）」）
        assertTrue(thrown.getMessage().contains("请稍后重试"), "锁冲突文案丢了：前端只会看到追踪码");

        assertEquals(0, entered.get(), "没拿到锁却进入了临界区 = 等于没有锁");
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("取锁被中断：恢复中断位并抛 409 文案，临界区不执行")
    void interruptedWhileWaitingIsRefused() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenThrow(new InterruptedException("interrupted"));
        AtomicInteger entered = new AtomicInteger();

        try {
            assertThrows(ConcurrentOperationException.class,
                    () -> new DistLock(client).runLocked("k", () -> {
                        entered.incrementAndGet();
                        return "x";
                    }));
            assertEquals(0, entered.get());
            assertTrue(Thread.currentThread().isInterrupted(), "中断位必须恢复，否则上层线程池会漏掉中断信号");
        } finally {
            Thread.interrupted(); // 清掉，避免污染同一线程上的后续测试
        }
    }

    @Test
    @DisplayName("Redis 不可用（fail-closed）：抛 ServiceNotReadyException，绝不在无锁下执行临界区")
    void redisDownIsFailClosed() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), any())).thenThrow(new IllegalStateException("connection refused"));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        AtomicInteger entered = new AtomicInteger();

        assertThrows(ServiceNotReadyException.class,
                () -> new DistLock(client).runLocked("k", () -> {
                    entered.incrementAndGet();
                    return "x";
                }));

        assertEquals(0, entered.get(), "锁服务不可用时放行 = 多实例下静默不互斥");
    }

    @Test
    @DisplayName("临界区抛异常：仍然释放锁（否则一次异常堵死后续所有请求）")
    void releasesLockOnException() {
        RedissonClient client = redisson(true);
        RLock lock = lockOf(client);

        assertThrows(RuntimeException.class,
                () -> new DistLock(client).runLocked("k", () -> {
                    throw new RuntimeException("boom");
                }));

        verify(lock).unlock();
    }

    @Test
    @DisplayName("释放锁本身失败：不影响临界区结果（只记日志）")
    void releaseFailureDoesNotMaskResult() {
        RedissonClient client = redisson(true);
        RLock lock = lockOf(client);
        org.mockito.Mockito.doThrow(new RuntimeException("redis-like down")).when(lock).unlock();

        assertEquals("ok", new DistLock(client).runLocked("k", () -> "ok"));
    }

    @Test
    @DisplayName("锁已被看门狗放手（非本线程持有）：不硬调 unlock，避免把成功请求变成 500")
    void doesNotUnlockWhenNotHeld() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = lock(true, false);
        when(client.getLock(anyString())).thenReturn(lock);

        assertEquals("ok", new DistLock(client).runLocked("k", () -> "ok"));
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("事务内：放锁推迟到 afterCompletion（在事务里提前放锁，并发会读到未提交的空结果）")
    void releaseDeferredUntilAfterCompletionInsideTransaction() {
        RedissonClient client = redisson(true);
        RLock lock = lockOf(client);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals("ok", new DistLock(client).runLocked("k", () -> "ok"));
            // 临界区结束、事务尚未提交：此时绝不能放锁
            verify(lock, never()).unlock();
            // 事务提交 → afterCompletion 才释放（Redisson 的 unlock 要求同线程，afterCompletion 正是同线程）
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(lock).unlock();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("afterCommit：无事务立即执行；有事务推迟到提交后（入队不能早于行可见）")
    void afterCommitDefersUntilCommitOnlyInsideTransaction() {
        AtomicInteger ran = new AtomicInteger();

        DistLock.afterCommit(ran::incrementAndGet);
        assertEquals(1, ran.get(), "无事务上下文应立即执行");

        TransactionSynchronizationManager.initSynchronization();
        try {
            DistLock.afterCommit(ran::incrementAndGet);
            assertEquals(1, ran.get(), "有事务上下文时不能立即执行");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertEquals(2, ran.get(), "提交后才执行");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
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
