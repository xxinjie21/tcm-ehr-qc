package com.tcm.ehr.controller;

import com.tcm.ehr.domain.dto.FiltersDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 报告 / 看板的缓存 key 口径（2026-10-28）。
 *
 * <p>要钉住的是：前端把筛选清空后发的是 {@code department=&start=&end=}（空串），
 * 而预热用的是空 {@link FiltersDTO}（null）。缓存 key 是 DTO 的 JSON 摘要，
 * `{"department":""}` 与 `{}` 会算成两把 key —— 不归一，预热写好的那把永远命不中，
 * 用户看到的仍是 2.3s 冷算。真实事故：预热上线后首页报告仍走了 5s 冷读，原因正是这里。</p>
 */
@DisplayName("StatsController.filters：空白参数归一（缓存 key 与预热同源）")
class StatsControllerFiltersTest {

    @Test
    @DisplayName("清空筛选（空串）与不传（null）产出同一个过滤对象 —— 否则预热 key 命不中")
    void blankParamsEqualMissingParams() {
        FiltersDTO missing = StatsController.filters(null, null, null, null, null, null);
        FiltersDTO blank = StatsController.filters("", "", "", "", "", "");

        assertEquals(missing, blank,
                "空串与 null 必须归一：前端清空筛选发的是 department=，预热用的是空 DTO");
        assertNull(blank.getDepartment(), "空白科室应归一成 null（RecordFilter 本就按 notBlank 判）");
        assertNull(blank.getGrade());
        assertNull(blank.getDateRange());
        assertNull(blank.getImportRange());
    }

    @Test
    @DisplayName("有值照常保留：科室 / 分级 / 两端齐全的时间区间")
    void realValuesAreKept() {
        FiltersDTO f = StatsController.filters("内科", "2024-01-01", "2024-12-31", "合格", "", "");

        assertEquals("内科", f.getDepartment());
        assertEquals("合格", f.getGrade());
        assertNotNull(f.getDateRange());
        assertEquals("2024-01-01", f.getDateRange().get(0));
        assertEquals("2024-12-31", f.getDateRange().get(1));
        assertNull(f.getImportRange(), "只给一端的导入区间不生效（与既有口径一致）");
    }

    @Test
    @DisplayName("单端时间仍不生效：只有两端齐全才落到 DTO")
    void oneSidedRangeIgnored() {
        FiltersDTO f = StatsController.filters(null, "2024-01-01", "", null, "2024-01-01", "");
        assertNull(f.getDateRange(), "接诊时间只给一端 = 未给");
        assertNull(f.getImportRange(), "导入时间只给一端 = 未给");
    }
}
