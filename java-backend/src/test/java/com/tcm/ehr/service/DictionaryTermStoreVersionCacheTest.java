package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.DictionaryTerm;
import com.tcm.ehr.domain.po.DictionaryVersion;
import com.tcm.ehr.mapper.DictionaryTermMapper;
import com.tcm.ehr.mapper.DictionaryVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 版本号缓存（批次 12 · 12a）的验收测试：**查询次数下降**。
 *
 * <p>12a 的验收就是这四个字加上「打点」。这里用 Mockito 的调用计数打在 {@code versionMapper} 上 ——
 * <b>方法无关</b>地数调用次数（不去断言它内部调的是 selectOne 还是 selectList），
 * 所以就算将来 {@code findVersion} 换了实现方式，这条测试仍然只测「有没有少查库」这件事。</p>
 *
 * <p>同时锁住**不脏读**：写路径（{@code replace}）必须让版本缓存失效，
 * 失效之后的下一次读要重新查库。</p>
 */
class DictionaryTermStoreVersionCacheTest {

    private static DictionaryTermStore svc(DictionaryTermMapper termMapper,
                                           DictionaryVersionMapper versionMapper) {
        return new DictionaryTermStore(termMapper, versionMapper,
                new tools.jackson.databind.ObjectMapper());
    }

    private static int versionCalls(DictionaryVersionMapper m) {
        return Mockito.mockingDetails(m).getInvocations().size();
    }

    private static DictionaryVersion freshVersion() {
        DictionaryVersion v = new DictionaryVersion();
        v.setOrgId("");
        v.setType("symptom");
        v.setVersion("v1");
        return v;
    }

    @Test
    @DisplayName("重复读同一组织同一类型 ⇒ 版本查询只发生一次（查询次数下降）")
    void repeatedReadQueriesVersionOnce() {
        DictionaryTermMapper termMapper = mock(DictionaryTermMapper.class);
        DictionaryVersionMapper versionMapper = mock(DictionaryVersionMapper.class);
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectMaps(any())).thenReturn(List.of());
        when(termMapper.selectList(any())).thenReturn(List.of());

        DictionaryTermStore store = svc(termMapper, versionMapper);
        store.readEffective("org-A", "symptom");
        int afterFirst = versionCalls(versionMapper);
        assertTrue(afterFirst > 0, "第一次读必须真的查过版本（前置条件）");

        // 再读 10 次：整个 readEffective（两层）都应命中版本缓存，不再查库
        for (int i = 0; i < 10; i++) {
            store.readEffective("org-A", "symptom");
        }
        assertEquals(afterFirst, versionCalls(versionMapper),
                "后续读取不应再查版本表 —— 这正是 12a 要的「查询次数下降」");
    }

    @Test
    @DisplayName("写路径必须让版本缓存失效：replace 之后的下一次读要重新查库（不脏读）")
    void replaceInvalidatesVersionCache() {
        DictionaryTermMapper termMapper = mock(DictionaryTermMapper.class);
        DictionaryVersionMapper versionMapper = mock(DictionaryVersionMapper.class);
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectMaps(any())).thenReturn(List.of());
        when(termMapper.selectList(any())).thenReturn(List.of());
        when(termMapper.delete(any())).thenReturn(0);
        // 注意：replace 传的是空列表 ⇒ 不会 insert，因此这里不 stub insert
        //（BaseMapper.insert 有重载，any() 会有歧义；不 stub 也就回避了这件事）

        DictionaryTermStore store = svc(termMapper, versionMapper);
        store.readEffective("org-A", "symptom");
        int afterRead = versionCalls(versionMapper);

        store.replace("org-A", "symptom", List.of());      // 写路径
        int afterWrite = versionCalls(versionMapper);
        assertTrue(afterWrite > afterRead, "写路径自身要读写版本行");

        store.readEffective("org-A", "symptom");           // 失效后的第一次读
        assertTrue(versionCalls(versionMapper) > afterWrite,
                "replace 之后必须重新查版本 —— 否则是「改了词表却看不到变化」的脏读");
    }

    @Test
    @DisplayName("TTL 到期后会重新查（跨实例改动的兜底路径）")
    void ttlExpiryRefetches() throws Exception {
        DictionaryTermMapper termMapper = mock(DictionaryTermMapper.class);
        DictionaryVersionMapper versionMapper = mock(DictionaryVersionMapper.class);
        when(versionMapper.selectOne(any())).thenReturn(null);
        when(versionMapper.selectList(any())).thenReturn(List.of());
        when(versionMapper.selectMaps(any())).thenReturn(List.of());
        when(termMapper.selectList(any())).thenReturn(List.of());

        DictionaryTermStore store = svc(termMapper, versionMapper);
        store.readEffective("org-A", "symptom");
        int afterFirst = versionCalls(versionMapper);

        // 真的等过 TTL（5 秒 + 余量）。不改成反射改字段：CachedVersion 是 record，
        // 字段 final 改不动；而为测试方便去改生产代码（加 setter / 注入 Clock）不值得 ——
        // 这条测试本来就不常跑，多 5 秒换来「TTL 真的生效」这个事实。
        Thread.sleep(5_200L);

        store.readEffective("org-A", "symptom");
        assertTrue(versionCalls(versionMapper) > afterFirst,
                "TTL 到期后必须重新查版本表 —— 这是跨实例改动的兜底");
    }

    @Test
    @DisplayName("事务内写路径把缓存失效推迟到提交后：提交前不暴露未提交词条，提交后必刷新（批次 26.8）")
    void replaceDefersInvalidationUntilAfterCommit() {
        DictionaryTermMapper termMapper = mock(DictionaryTermMapper.class);
        DictionaryVersionMapper versionMapper = mock(DictionaryVersionMapper.class);
        // 每次都回一个新对象：upsertVersion 会就地改写 findVersion 拿到的版本行，
        // 若复用同一实例，提交前的读会看到一个被改成新哈希的版本而误判缓存未失效
        when(versionMapper.selectOne(any())).thenAnswer(inv -> freshVersion());
        when(versionMapper.selectList(any())).thenAnswer(inv -> List.of(freshVersion()));
        when(versionMapper.selectMaps(any())).thenReturn(List.of());

        DictionaryTerm oldRow = new DictionaryTerm();
        oldRow.setOrgId("");
        oldRow.setType("symptom");
        oldRow.setStandardTerm("旧");
        DictionaryTerm newRow = new DictionaryTerm();
        newRow.setOrgId("");
        newRow.setType("symptom");
        newRow.setStandardTerm("新");
        // 第一次装载读到旧行，缓存失效后的下一次装载读到新行
        when(termMapper.selectList(any())).thenReturn(List.of(oldRow), List.of(newRow));
        when(termMapper.delete(any())).thenReturn(0);

        DictionaryTermStore store = svc(termMapper, versionMapper);
        assertEquals("旧", store.readEffective("", "symptom").get(0).getStandardTerm(),
                "前置：第一次读装载旧词条");

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.replace("", "symptom", List.of());
            assertEquals("旧", store.readEffective("", "symptom").get(0).getStandardTerm(),
                    "事务未提交：不得失效缓存去读到尚未提交的新词条");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertEquals("新", store.readEffective("", "symptom").get(0).getStandardTerm(),
                "提交后必须失效缓存并重新装载");
    }
}
