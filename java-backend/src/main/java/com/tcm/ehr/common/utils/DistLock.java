package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.mapper.DbLockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 跨实例互斥（批次 16 工作项 1 的最小实现）。
 *
 * 锁粒度按业务 key（如 dict:herb:org-A），不用全局单键 ——
 * 全局单键会让「A 组织导词典」与「B 组织跑质控」互相排队，明明毫无关系。
 *
 * 拿不到锁时默认抛异常，不放行：这是本类与「锁拿不到就继续」的写法之间
 * 最要紧的区别 —— 后者等于没有锁，且只在高并发时才发作，极难复现。
 *
 * 不引入 Redisson：见 {@link DbLockMapper} 的说明。锁只降冲突概率，
 * 正确性由 DB 唯一键与事务承担。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistLock {

    /** 拿不到锁时的等待秒数（与批次 9 质控提交一致） */
    private static final int WAIT_SECONDS = 3;

    private final DbLockMapper mapper;

    /**
     * 在锁内执行；拿不到锁抛 {@link ConcurrentOperationException}（409 + code=409，可展示）。
     *
     * @param lockName 锁名（建议格式 业务:key）
     * @param body     临界区
     * @throws ConcurrentOperationException 等待超时未拿到锁
     */
    public <T> T runLocked(String lockName, java.util.function.Supplier<T> body) {
        Integer got = mapper.acquire(lockName, WAIT_SECONDS);
        if (got == null || got != 1) {
            // 绝不放行：并发进入临界区会让 ES 索引出现交错删除 + 灌入的混合状态
            throw new ConcurrentOperationException("有另一个相同操作正在进行，请稍后重试");
        }
        // MySQL 命名锁是**连接级**的，acquire 与 release 必须落在同一条物理连接上：
        //  · 事务内两者共用事务连接，且必须等提交/回滚后再释放 —— 若在 finally 里提前放锁，
        //    并发的「查重 + 插入」会读到尚未提交的空结果，防重形同虚设；
        //  · 不进事务时两次 mapper 调用可能拿到不同连接，RELEASE_LOCK 释放在别的连接上
        //    等于没释放（锁泄漏到该连接关闭为止）。
        boolean deferred = TransactionSynchronizationManager.isSynchronizationActive();
        if (deferred) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseQuietly(lockName);
                }
            });
        }
        try {
            return body.get();
        } finally {
            if (!deferred) {
                releaseQuietly(lockName);
            }
        }
    }

    /** 释放失败只记录：连接最终关闭时 DB 仍会释放它持有的命名锁，不该因放锁失败掩盖业务结果 */
    private void releaseQuietly(String lockName) {
        try {
            mapper.release(lockName);
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
