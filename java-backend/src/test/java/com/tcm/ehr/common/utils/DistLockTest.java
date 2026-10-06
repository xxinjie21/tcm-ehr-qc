package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.mapper.DbLockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * 最要紧的一条是拿不到锁不放行：写成「拿不到就继续」等于没有锁，
 * 而且只在高并发时发作 —— 这种 bug 现场几乎无法复现。
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

        ConcurrentOperationException thrown = assertThrows(ConcurrentOperationException.class,
                () -> new DistLock(mapper).runLocked("k", () -> {
                    entered.incrementAndGet();
                    return "x";
                }));
        // 文案要能被前端直接展示（批次 25 工作项 1：不再落兜底 500 的「系统异常（追踪码 …）」）
        assertTrue(thrown.getMessage().contains("请稍后重试"), "锁冲突文案丢了：前端只会看到追踪码");

        assertEquals(0, entered.get(), "没拿到锁却进入了临界区 = 等于没有锁");
        verify(mapper, never()).release(anyString());
    }

    @Test
    @DisplayName("取锁返回 null（DB 出错）同样拒绝，不当成功处理")
    void nullResultIsAlsoRefused() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(null);

        assertThrows(ConcurrentOperationException.class,
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
    @DisplayName("事务内：放锁推迟到 afterCompletion（在事务里提前放锁，并发会读到未提交的空结果）")
    void releaseDeferredUntilAfterCompletionInsideTransaction() {
        DbLockMapper mapper = mock(DbLockMapper.class);
        when(mapper.acquire(anyString(), anyInt())).thenReturn(1);
        when(mapper.release(anyString())).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals("ok", new DistLock(mapper).runLocked("k", () -> "ok"));
            // 临界区结束、事务尚未提交：此时绝不能放锁
            verify(mapper, never()).release(anyString());
            // 事务提交 → afterCompletion 才释放（且落在同一事务连接上）
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            verify(mapper).release("k");
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
