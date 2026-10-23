package com.tcm.ehr.common.utils;

import com.tcm.ehr.service.IDictionaryTermStore;

/**
 * 批级词典元数据：一次批处理里 effectiveDictVersion 与 effectiveTermCount
 * <b>只查一次库</b>。
 *
 * <b>为什么需要它</b>：orgId 在一批内恒定，同一批也不该盖上两个不同的版本戳；
 * 而逐条去取元数据会让 4 万条批处理发出十几万次 SQL（两条元数据 × 每条病历）。
 * 这类「同一问题在一处已解决、另一处漏掉」的重复出现，是把缓存收进这个共享组件的原因。
 *
 * <b>惰性</b>：按「批」持有、首次使用时才取 —— 没有待处理项（筛出空集）时一次查库都不会发。
 *
 * <b>公开构造是为了让「只取一次」能被同步测试直接钉住</b> —— 线程里的流程难测，
 * 但这条缓存行为好测，而漏掉它正是要修的东西。
 */
public final class DictMeta {

    private final IDictionaryTermStore store;
    private final String orgId;
    private String version;
    private int termCount;
    private boolean loaded;

    public DictMeta(IDictionaryTermStore store, String orgId) {
        this.store = store;
        this.orgId = orgId;
    }

    /** 该批生效词典的版本指纹（基础层 ∪ 本组织） */
    public String version() {
        load();
        return version;
    }

    /** 该批生效词典的词条总数 */
    public int termCount() {
        load();
        return termCount;
    }

    /**
     * 惰性装载。
     *
     * <b>必须同步</b>：批解析已支持「一个任务内多线程并行处理病历」，多个线程会同时首次
     * 进入这里。原先用普通字段 + loaded 标志，既没有 happens-before 保证
     * （可能读到 {@code loaded == true} 而 {@code version == null}，把版本戳打成 null），
     * 也会并发发出重复查询。加锁一次同时解决「只查一次」与「值可见」。
     */
    private synchronized void load() {
        if (!loaded) {
            // 先算到局部变量再赋值：赋值顺序不影响正确性（都在锁内），但能保证
            // 不会出现「version 已写、termCount 还是默认 0」被别的线程看到的中间态
            String v = store.effectiveDictVersion(orgId);
            int c = store.effectiveTermCount(orgId);
            version = v;
            termCount = c;
            loaded = true;
        }
    }
}
