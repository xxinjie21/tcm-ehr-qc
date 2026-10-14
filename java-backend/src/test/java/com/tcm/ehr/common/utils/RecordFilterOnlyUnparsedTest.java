package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「只解析未解析的」过滤条件：{@code structured_data IS NULL}。
 *
 * <p>锁两件事：① 打开时两个入口（SearchDTO 计数 / FiltersDTO 批提交）都追加 IS NULL 条件；
 * ② 缺省时<b>不加</b>任何 structured_data 条件（不影响既有查询）。用 IS NULL 而非 {@code = ''}
 * 是因为该列是 MySQL JSON 类型（空串存不进、比较会报 Invalid JSON text）。</p>
 */
@DisplayName("RecordFilter：仅未解析（structured_data IS NULL）")
class RecordFilterOnlyUnparsedTest {

    private static final String ORG = "org-unparsed-1";

    @Test
    @DisplayName("SearchDTO.onlyUnparsed=true → 段里含 structured_data IS NULL")
    void searchDtoOnlyUnparsed() {
        SearchDTO dto = new SearchDTO();
        dto.setOnlyUnparsed(true);
        String seg = RecordFilter.build(ORG, dto).getSqlSegment();
        assertTrue(seg.contains("structured_data IS NULL"), seg);
    }

    @Test
    @DisplayName("FiltersDTO.onlyUnparsed=true → 段里含 structured_data IS NULL")
    void filtersDtoOnlyUnparsed() {
        FiltersDTO dto = new FiltersDTO();
        dto.setOnlyUnparsed(true);
        String seg = RecordFilter.build(ORG, dto).getSqlSegment();
        assertTrue(seg.contains("structured_data IS NULL"), seg);
    }

    @Test
    @DisplayName("缺省/为 false → 不加 structured_data 条件")
    void absentOnlyUnparsedAddsNothing() {
        String segDefault = RecordFilter.build(ORG, new SearchDTO()).getSqlSegment();
        assertFalse(segDefault.contains("structured_data"), segDefault);

        SearchDTO off = new SearchDTO();
        off.setOnlyUnparsed(false);
        String segOff = RecordFilter.build(ORG, off).getSqlSegment();
        assertFalse(segOff.contains("structured_data"), segOff);
    }
}
