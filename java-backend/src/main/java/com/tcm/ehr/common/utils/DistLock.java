package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.exception.ServiceNotReadyException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * 跨实例互斥（批次 16.1：Redisson 分布式锁）。
 *
 * <p>锁粒度按业务 key（如 dict:herb:org-A），不用全局单键 ——
 * 全局单键会让「A 组织导词典」与「B 组织跑质控」互相排队，明明毫无关系。</p>
 *
 * <p><b>为什么是 Redis/Redisson</b>：{@code synchronized} / {@code ReentrantLock} 在多实例下
 * <b>静默失效</b>（不报错、只是不互斥）；上一版用 MySQL 命名锁 {@code GET_LOCK}，
 * 那是「借 DB 的连接级锁」，语义与连接绑定、跨服务边界不好复用。Redisson 的 RLock 是
 * 所有实例共享的锁，且自带看门狗续期：不进事务的长临界区也不会因为固定 lease 突然过期。</p>
 *
 * <p><b>fail-closed</b>：拿不到锁抛 {@link ConcurrentOperationException}；
 * Redis 本身不可用抛 {@link ServiceNotReadyException}（503）。两者都<b>绝不放行</b> ——
 * 写成「拿不到锁就继续」等于没有锁，且只在高并发时发作，极难复现。</p>
 *
 * <p>锁只降冲突概率，正确性仍由 DB 唯一键与事务承担。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistLock {

    /** 拿不到锁时的等待秒数（与批次 9 质控提交一致） */
    private static final int WAIT_SECONDS = 3;

    private final RedissonClient redisson;

    /**
     * 在锁内执行；拿不到锁抛 {@link ConcurrentOperationException}（409 + code=409，可展示）。
     *
     * @param lockName 锁名（建议格式 业务:key）
     * @param body     临界区
     * @throws ConcurrentOperationException 等待超时未拿到锁
     * @throws ServiceNotReadyException     Redis 不可用（fail-closed，不放行）
     */
    public <T> T runLocked(String lockName, java.util.function.Supplier<T> body) {
        RLock lock = redisson.getLock(lockName);
        if (!tryLock(lock, lockName)) {
            // 绝不放行：并发进入临界区会让 ES 索引出现交错删除 + 灌入的混合状态
            throw new ConcurrentOperationException("有另一个相同操作正在进行，请稍后重试");
        }
        // 事务内必须等提交/回滚后再放锁：若在 finally 里提前放锁，并发的
        // 「查重 + 插入」会读到尚未提交的空结果，防重形同虚设。
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockQuietly(lock, lockName);
                }
            });
        }
        try {
            return body.get();
        } finally {
            if (!deferred) {
                unlockQuietly(lock, lockName);
            }
        }
    }

    /** 取锁：不传 leaseTime，走 Redisson 看门狗（默认 30s，持有期间自动续期） */
    private boolean tryLock(RLock lock, String lockName) {
        try {
            return lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrentOperationException("等待其它相同操作时被中断，请稍后重试");
        } catch (RuntimeException e) {
            // Redis 连接失败 / 命令超时：这是依赖故障，不是「有人正在操作」。
            // 报 503 + code=1011（消息可展示），同样不放行 —— 没有锁就没有互斥。
            log.error("[互斥] 锁服务不可用 lock={}: {}", lockName, e.getMessage());
            throw new ServiceNotReadyException("互斥锁服务（Redis）不可用，请稍后重试");
        }
    }

    /**
     * 释放失败只记录。
     *
     * <p>看门狗停止续期后锁会自然过期（默认 30s），不该因放锁失败掩盖业务结果；
     * 也不能在未持有时硬调 {@code unlock()} —— 那会抛
     * {@code IllegalMonitorStateException} 把一次成功的业务请求变成 500。</p>
     */
    private void unlockQuietly(RLock lock, String lockName) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[互斥] 释放锁失败 lock={}: {}", lockName, e.getMessage());
        }
    }

    /**
     * 提交后执行（批次 26.2）。
     *
     * <p>入队必须等事务提交、任务行可见之后再做，否则工作线程可能在行可见前就取到 id、
     * 查不到任务。没有活动事务时立即执行（单测 / 直调路径）。</p>
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 词典索引重建的锁名：按「类型 + 组织」划分 */
    public static String dictRebuildLock(String type, String orgId) {
        return "tcm:dict:rebuild:" + type + ":" + (orgId == null ? "" : orgId);
    }
}
