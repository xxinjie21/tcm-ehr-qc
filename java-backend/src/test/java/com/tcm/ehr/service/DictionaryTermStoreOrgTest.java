package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.mapper.DictionaryTermMapper;
import com.tcm.ehr.mapper.DictionaryVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词典存储的版本语义契约。
 *
 * <p>只锁<b>纯逻辑</b>：内容版本怎么算、什么算「已同步」。
 * SQL 层的 org_id 过滤（组织隔离）不在这里测 —— {@code QueryWrapper} 是不透明结构，
 * 硬测只会测到自己写的 fake。组织隔离真正的守门人是
 * {@code EsOrgKeyTest}（ES 侧的 org_id 过滤与文档 id）与集成层的归一验收。</p>
 */
class DictionaryTermStoreOrgTest {

    private static DictionaryTermStore newStore() {
        return new DictionaryTermStore(
                mock(DictionaryTermMapper.class),
                mock(DictionaryVersionMapper.class),
                new ObjectMapper());
    }

    @Test
    @DisplayName("内容版本：同一份词条换个导入顺序，版本必须不变")
    void contentVersionIsOrderIndependent() {
        DictionaryTermStore store = newStore();
        List<TermEntry> a = List.of(
                new TermEntry("甘草", List.of("国老"), "药典"),
                new TermEntry("人参", List.of("上党人参"), "药典"));
        List<TermEntry> b = List.of(
                new TermEntry("人参", List.of("上党人参"), "药典"),
                new TermEntry("甘草", List.of("国老"), "药典"));
        assertEquals(store.contentVersion(a), store.contentVersion(b),
                "导入顺序不同不应产生不同版本，否则每次导入都白白触发一次 ES 全量重建");
    }

    @Test
    @DisplayName("内容版本：词条增减必须改变版本")
    void contentVersionChangesWithContent() {
        DictionaryTermStore store = newStore();
        List<TermEntry> one = List.of(new TermEntry("甘草", List.of(), "药典"));
        List<TermEntry> two = List.of(
                new TermEntry("甘草", List.of(), "药典"),
                new TermEntry("人参", List.of(), "药典"));
        assertNotEquals(store.contentVersion(one), store.contentVersion(two),
                "多一条词条必须换版本，否则改了词典却跳过重建");
    }

    @Test
    @DisplayName("isSynced：没有版本行 = 未同步（不能默认当成已同步）")
    void noVersionRowMeansNotSynced() {
        DictionaryVersionMapper vm = mock(DictionaryVersionMapper.class);
        when(vm.selectOne(any())).thenReturn(null);
        DictionaryTermStore store = new DictionaryTermStore(
                mock(DictionaryTermMapper.class),
                vm, new ObjectMapper());

        assertTrue(!store.isSynced("org-A", "herb"),
                "查不到版本行时必须判未同步；默认已同步会让首次启动跳过重建、索引里什么都没有");
    }

    @Test
    @DisplayName("isSynced：indexedVersion 落后 version = 未同步（ES 灌失败必须看得出来）")
    void staleIndexedVersionMeansNotSynced() {
        DictionaryVersionMapper vm = mock(DictionaryVersionMapper.class);
        com.tcm.ehr.domain.po.DictionaryVersion row = new com.tcm.ehr.domain.po.DictionaryVersion();
        row.setOrgId("");
        row.setType("herb");
        row.setVersion("v2");
        row.setIndexedVersion("v1");
        when(vm.selectOne(any())).thenReturn(row);
        DictionaryTermStore store = new DictionaryTermStore(
                mock(DictionaryTermMapper.class),
                vm, new ObjectMapper());

        assertTrue(!store.isSynced("", "herb"),
                "indexed_version 落后于 version 时必须判未同步 —— 这正是「库已改、ES 没跟上」的信号");
    }
}
