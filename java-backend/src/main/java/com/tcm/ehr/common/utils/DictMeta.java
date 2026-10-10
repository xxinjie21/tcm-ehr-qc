package com.tcm.ehr.common.utils;

import com.tcm.ehr.service.IDictionaryTermStore;

/**
 * 批级词典元数据：一次批处理里 {@code effectiveDictVersion} 与 {@code effectiveTermCount}
 * <b>只查一次库</b>。
 *
 * <p><b>为什么需要它</b>：{@code orgId} 在一批内恒定，同一批也不该盖上两个不同的版本戳；
 * 而逐条去取元数据会让 4 万条批处理发出十几万次 SQL（两条元数据 × 每条病历）。
 * 这类「同一问题在一处已解决、另一处漏掉」的重复出现，是把缓存收进这个共享组件的原因。</p>
 *
 * <p><b>惰性</b>：按「批」持有、首次使用时才取 —— 没有待处理项（筛出空集）时一次查库都不会发。</p>
 *
 * <p><b>公开构造是为了让「只取一次」能被同步测试直接钉住</b> —— 线程里的流程难测，
 * 但这条缓存行为好测，而漏掉它正是要修的东西。</p>
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

    private void load() {
        if (!loaded) {
            version = store.effectiveDictVersion(orgId);
            termCount = store.effectiveTermCount(orgId);
            loaded = true;
        }
    }
}
