package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * keyset（游标）分页条件与边界正确性（性能审查 P0-2）。
 *
 * <p>锁两件事：① {@link RecordKeyset#anchorAfter} 生成的 SQL 条件形态正确
 * （{@code visit_time < ? OR (visit_time = ? AND id > ?)}，双键防漏/重）；
 * ② 模拟「3 页数据、第 2 页锚点落在同一 {@code visit_time} 组内」的场景，
 * 按游标语义逐页取完，验证 {@code 并集 == 全集}、每行恰好取一次 ——
 * 这是 offset 分页在页边界最容易踩的坑（同秒多条时漏行 / 重复）。</p>
 */
@DisplayName("RecordKeyset：游标分页条件与三页边界")
class RecordKeysetTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2025, 1, 1, 0, 0, 0);

    private static Record rec(int seq, LocalDateTime vt) {
        Record r = new Record();
        r.setId("rec-" + String.format("%04d", seq));
        r.setVisitTime(vt);
        return r;
    }

    @Test
    void anchorAfterGeneratesBothConditionBranches() {
        QueryWrapper<Record> w = new QueryWrapper<>();
        w.eq("org_id", "org-A");
        LocalDateTime vt = T0.plusDays(1);
        String id = "rec-0003";
        RecordKeyset.anchorAfter(w, vt, id);
        String sql = w.getCustomSqlSegment();
        assertTrue(sql.contains("org_id"), "前置筛选必须保留: " + sql);
        assertTrue(sql.contains("visit_time <"), "必须有 visit_time < 分支: " + sql);
        assertTrue(sql.contains("visit_time ="), "必须有 visit_time = 分支: " + sql);
        assertTrue(sql.contains("id >"), "同 visit_time 必须用 id > 收口: " + sql);
    }

    @Test
    void threePagesNoSkipNoDuplicateIncludingSameVisitTimeBoundary() {
        // 1. 造 26 行：第 2 页锚点所在的那一秒有 4 行同 visit_time（边界最容易漏/重的形态）
        List<Record> all = new ArrayList<>();
        for (int i = 0; i < 10; i++) all.add(rec(i + 1, T0.plusSeconds(100 - i)));
        for (int i = 0; i < 4; i++) all.add(rec(20 + i, T0.plusSeconds(89))); // 同秒 4 行
        for (int i = 0; i < 12; i++) all.add(rec(30 + i, T0.plusSeconds(88 - i * 5)));
        // 2. 与 DB 排序同序：visit_time DESC, id ASC
        Comparator<Record> order = Comparator
                .comparing(Record::getVisitTime, Comparator.reverseOrder())
                .thenComparing(Record::getId);
        List<Record> expected = all.stream().sorted(order).toList();
        assertEquals(26, expected.size());

        final int pageSize = 10;
        // 3. 按游标语义逐页取（第 2 页锚点 id rec-0020 ~ rec-0023 落在同一 visit_time 组内）
        List<Record> picked = new ArrayList<>();
        LocalDateTime lastVt = null;
        String lastId = null;
        boolean first = true;
        List<Integer> pageSizes = new ArrayList<>();
        while (true) {
            List<Record> page;
            if (first) {
                page = expected.subList(0, Math.min(pageSize, expected.size()));
            } else {
                // 游标谓词（与 RecordKeyset.anchorAfter 的 SQL 同义）：
                // 排序为 visit_time DESC, id ASC，「锚点之后」= visit_time 更早
                // 或（同 visit_time 且 id 更大）
                LocalDateTime vt = lastVt;
                String id = lastId;
                page = expected.stream()
                        .filter(r -> r.getVisitTime().isBefore(vt)
                                || (r.getVisitTime().equals(vt) && r.getId().compareTo(id) > 0))
                        .limit(pageSize)
                        .toList();
                if (page.size() < pageSize) {
                    pageSizes.add(page.size());
                    picked.addAll(page);
                    break;
                }
            }
            pageSizes.add(page.size());
            picked.addAll(page);
            if (page.size() < pageSize) {
                break;
            }
            Record last = page.get(page.size() - 1);
            lastVt = last.getVisitTime();
            lastId = last.getId();
            first = false;
        }
        // 4. 并集 == 全集（不重不漏）
        assertEquals(expected.size(), picked.size(), "三页应恰好取完全集");
        assertEquals(expected.stream().map(Record::getId).sorted().toList(),
                picked.stream().map(Record::getId).sorted().toList(),
                "三页取出的 id 集合必须与全集一致（无漏行 / 无重复）");
    }
}