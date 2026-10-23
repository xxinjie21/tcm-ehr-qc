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
 * 锁粒度按业务 key（如 dict:herb:org-A），不用全局单键 ——
 * 全局单键会让「A 组织导词典」与「B 组织跑质控」互相排队，明明毫无关系。
 *
 * <b>为什么是 Redis/Redisson</b>：synchronized / ReentrantLock 在多实例下
 * <b>静默失效</b>（不报错、只是不互斥）；上一版用 MySQL 命名锁 GET_LOCK，
 * 那是「借 DB 的连接级锁」，语义与连接绑定、跨服务边界不好复用。Redisson 的 RLock 是
 * 所有实例共享的锁，且自带看门狗续期：不进事务的长临界区也不会因为固定 lease 突然过期。
 *
 * <b>fail-closed</b>：拿不到锁抛 {@link ConcurrentOperationException}；
 * Redis 本身不可用抛 {@link ServiceNotReadyException}（503）。两者都<b>绝不放行</b> ——
 * 写成「拿不到锁就继续」等于没有锁，且只在高并发时发作，极难复现。
 *
 * 锁只降冲突概率，正确性仍由 DB 唯一键与事务承担。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistLock {

    /**
     * 默认等待秒数：用于<b>批任务提交</b>与<b>启动期对账</b>这类「稍等一下也没关系」的场景。
     *
     * 在线写路径（词典导入 / 提案合并 / 索引重建）应显式传 {@code 0}
     * —— 见 {@link #runLocked(String, int, java.util.function.Supplier)}。
     */
    private static final int WAIT_SECONDS = 3;

    /**
     * 在线写路径的等待秒数：<b>不等待</b>，锁被占就立刻回 409。
     *
     * 为什么在线路径要等 0：等待时长会被直接叠加到用户的 HTTP 响应时间上，
     * 而冲突本身就说明「有人正在做同一件事」，让用户等 3 秒再告诉他同一句话没有意义。
     * 批任务提交不同 —— 那里的锁冲突意味着「已有任务在跑」，等一下往往就能提交上。
     */
    public static final int NO_WAIT = 0;

    private final RedissonClient redisson;

    /**
     * 在锁内执行，使用默认等待时长（3 秒）；拿不到锁抛
     * {@link ConcurrentOperationException}（409 + code=409，可展示）。
     *
     * 仅用于批任务提交 / 启动期对账这类可以稍等的场景；在线写路径请用
     * {@link #runLocked(String, int, java.util.function.Supplier)} 并传 {@link #NO_WAIT}。
     *
     * @param lockName 锁名（建议格式 业务:key）
     * @param body     临界区
     * @throws ConcurrentOperationException 等待超时未拿到锁
     * @throws ServiceNotReadyException     Redis 不可用（fail-closed，不放行）
     */
    public <T> T runLocked(String lockName, java.util.function.Supplier<T> body) {
        return runLocked(lockName, WAIT_SECONDS, body);
    }

    /**
     * 在锁内执行，等待时长由调用方决定。
     *
     * @param lockName    锁名（建议格式 业务:key）
     * @param waitSeconds 拿不到锁时的等待秒数；传 {@link #NO_WAIT}（0）= 只尝试一次、立刻失败
     * @param body        临界区
     * @throws ConcurrentOperationException 等待超时未拿到锁
     * @throws ServiceNotReadyException     Redis 不可用（fail-closed，不放行）
     */
    public <T> T runLocked(String lockName, int waitSeconds, java.util.function.Supplier<T> body) {
        RLock lock = redisson.getLock(lockName);
        if (!tryLock(lock, lockName, waitSeconds)) {
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

    /**
     * 取锁：不传 leaseTime，走 Redisson 看门狗（默认 30s，持有期间自动续期）。
     *
     * {@code waitSeconds = 0} 时 Redisson 只做一次尝试（waitTime ≤ 0 视为「不等待」），
     * 正好对应在线写路径的「锁被占就立刻 409」。
     */
    private boolean tryLock(RLock lock, String lockName, int waitSeconds) {
        try {
            return lock.tryLock(waitSeconds, TimeUnit.SECONDS);
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
     * 看门狗停止续期后锁会自然过期（默认 30s），不该因放锁失败掩盖业务结果；
     * 也不能在未持有时硬调 {@code unlock()} —— 那会抛
     * IllegalMonitorStateException 把一次成功的业务请求变成 500。
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
     * 入队必须等事务提交、任务行可见之后再做，否则工作线程可能在行可见前就取到 id、
     * 查不到任务。没有活动事务时立即执行（单测 / 直调路径）。
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
        return RedisKeys.PREFIX + "dict:rebuild:" + type + ":" + (orgId == null ? "" : orgId);
    }
}
