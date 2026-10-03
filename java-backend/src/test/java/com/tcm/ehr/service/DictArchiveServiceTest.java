package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.DictArchiveTerm;
import com.tcm.ehr.domain.po.DictArchiveVersion;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.mapper.DictArchiveTermMapper;
import com.tcm.ehr.mapper.DictArchiveVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 归档的「5 份限额」契约（批次 17）。
 *
 * <p>锁住两件最容易做错、且错了<b>不报错</b>的事：<br>
 * ① 超限时只删<b>快照</b>，版本元信息一律保留 —— 删了元信息，版本号就断档、审计链失效；<br>
 * ② 基础层（{@code org_id=''}）与各组织层<b>独立计数</b> —— 混算会让基础层的归档
 * 把组织层的挤掉，或反过来。</p>
 */
class DictArchiveServiceTest {

    private DictArchiveVersionMapper versionMapper;
    private DictArchiveTermMapper termMapper;
    private DictArchiveService svc;

    private void setUp() {
        versionMapper = mock(DictArchiveVersionMapper.class);
        termMapper = mock(DictArchiveTermMapper.class);
        svc = new DictArchiveService(versionMapper, termMapper,
                new tools.jackson.databind.ObjectMapper());
    }

    /** MAX(version_no) 查询返回单行；archive() 内部第 1 次 selectList 就是它 */
    private static DictArchiveVersion maxRow(int no) {
        DictArchiveVersion row = new DictArchiveVersion();
        row.setVersionNo(no);
        return row;
    }

    /** 让 MAX(version_no) 查询返回指定值 */
    private void maxVersionNo(int no) {
        DictArchiveVersion row = new DictArchiveVersion();
        row.setVersionNo(no);
        when(versionMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(row)), new ArrayList<>());
    }

    private static List<TermEntry> terms(int n) {
        List<TermEntry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new TermEntry("词" + i, List.of("别名" + i), "来源"));
        }
        return out;
    }

    @Test
    @DisplayName("版本号从 1 起、逐次递增（不重号）")
    void versionNoIncrements() {
        setUp();
        maxVersionNo(3);

        int no = svc.archive("org-A", "herb", terms(2), null, "admin", null);

        assertEquals(4, no);
    }

    @Test
    @DisplayName("无任何归档记录时，版本号从 1 开始")
    void firstVersionIsOne() {
        setUp();
        when(versionMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertEquals(1, svc.archive("", "herb", terms(1), null, "admin", null));
    }

    @Test
    @DisplayName("不超过 5 份：一次快照都不删")
    void keepsSnapshotsUnderLimit() {
        setUp();
        // 第 1 次 selectList 取 MAX(version_no)=3；第 2 次是 prune 重查，
        // 生产里此时已含刚插入的新版本 → 共 4 个，仍未超 5
        when(versionMapper.selectList(any())).thenReturn(
                List.of(maxRow(3)), versionRows(4));

        svc.archive("org-A", "herb", terms(2), null, "admin", null);

        verify(termMapper, never()).delete(any());
    }

    @Test
    @DisplayName("超过 5 份：只删最老版本的【快照】，元信息一条不删")
    void prunesOnlySnapshotsBeyondLimit() {
        setUp();
        // 第 1 次 MAX=5；第 2 次 prune 重查已含新版本 → 共 6 个，超限应清理最老 1 份
        when(versionMapper.selectList(any())).thenReturn(
                List.of(maxRow(5)), versionRows(6));

        svc.archive("org-A", "herb", terms(2), null, "admin", null);

        verify(termMapper).delete(any());
        // ★ 元信息绝不删：删了版本号就断档，审计链失效
        verify(versionMapper, never()).delete(any());
        verify(versionMapper, never()).deleteById(anyString());
    }

    @Test
    @DisplayName("基础层与组织层归档独立：基础层写入不会去动组织层的版本号")
    void baseLayerAndOrgCountIndependently() {
        setUp();
        // 基础层此前没有任何归档
        when(versionMapper.selectList(any())).thenReturn(new ArrayList<>());

        assertEquals(1, svc.archive("", "herb", terms(1), null, "admin", null));
        // 「org-A 有 7 号版本」这条事实不该让基础层从 7 开始
        assertEquals(1, svc.archive("", "pattern", terms(1), null, "admin", null));
    }

    @Test
    @DisplayName("归档内容取的是合并【之后】的基线（快照写入的是传入的 entries）")
    void archivesTheProvidedBaseline() {
        setUp();
        maxVersionNo(1);
        when(versionMapper.selectList(any())).thenReturn(new ArrayList<>(), new ArrayList<>());

        List<TermEntry> merged = terms(3);
        svc.archive("org-A", "herb", merged, "p1", "owner", "同意");

        verify(termMapper, org.mockito.Mockito.times(3)).insert(any(DictArchiveTerm.class));
    }

    private static List<DictArchiveVersion> versionRows(int n) {
        Map<String, Object> ignored = new LinkedHashMap<>();
        List<DictArchiveVersion> out = new ArrayList<>();
        for (int i = n; i >= 1; i--) {
            DictArchiveVersion v = new DictArchiveVersion();
            v.setId("v" + i);
            v.setVersionNo(i);
            out.add(v);
        }
        return out;
    }
}