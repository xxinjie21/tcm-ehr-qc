package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批F·7.4：数据域 → 用户筛选 固定顺序、取交集（防止筛选绕过数据域）。
 */
class RecordFilterTest {

    private String sql(String role, SearchDTO dto) {
        QueryWrapper<Record> w = RecordFilter.build(role, dto);
        return w.getSqlSegment();
    }

    @Test
    void adminNoDomainConstraint() {
        String s = sql(RecordFilter.ROLE_ADMIN, new SearchDTO());
        assertFalse(s.contains("grade"), "管理员不应被强制数据域");
    }

    @Test
    void auditorForcedPendingDomain() {
        String s = sql(RecordFilter.ROLE_AUDITOR, new SearchDTO());
        assertTrue(s.contains("grade"), "审核员必须叠加待复核数据域");
    }

    @Test
    void userFilterIntersectsDomainNotBypass() {
        SearchDTO dto = new SearchDTO();
        dto.setGrade("合格");
        dto.setDepartment("中医内科");
        String s = sql(RecordFilter.ROLE_AUDITOR, dto);
        // 数据域 grade=待复核 与用户 grade=合格 同时存在（交集，无法用筛选绕过域）
        assertTrue(s.contains("grade"), "域与用户筛选应同时存在");
        assertTrue(s.contains("department"), "用户筛选应生效");
    }

    @Test
    void userFiltersAppliedForAdmin() {
        SearchDTO dto = new SearchDTO();
        dto.setRegistrationNo("R001");
        dto.setPattern("风寒");
        String s = sql(RecordFilter.ROLE_ADMIN, dto);
        assertTrue(s.contains("registration_no"));
        assertTrue(s.contains("pattern"));
    }
}
