package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据域 → 用户筛选 固定顺序、取交集（阶段2：数据域 = 课题组）。
 *
 * <p>原设计用 {@code grade}（质控结论）当权限维度，本测试随之改写：
 * 现在数据域是 {@code org_id}，{@code grade} 只做用户可选筛选。视图重构的正确性
 * 证据只在一处：数据域必须出现在 SQL 里、且用户筛选不能绕过它。</p>
 */
class RecordFilterTest {

    private String sql(String orgId, SearchDTO dto) {
        QueryWrapper<Record> w = RecordFilter.build(orgId, dto);
        return w.getSqlSegment();
    }

    /** 有组：必须出现 org_id 等值条件（数据域是组的硬约束） */
    @Test
    void groupEnforcesGroupIdConstraint() {
        String s = sql("grp-a", new SearchDTO());
        assertTrue(s.contains("org_id"), "有组时必须有 org_id 条件：" + s);
    }

    /**
     * ⚠️ fail-closed：无组用户必须查不到任何东西，绝不能退化成「不过滤 = 全库」。
     *
     * <p>这是本改造<b>最关键的一行代码</b>的测试。实现靠一个与 UUID 格式不可能撞上的
     * 哨兵值 {@code id = '\\u0000__no_group__'} —— 任何导入数据都不可能命中它。</p>
     */
    @Test
    void noGroupFailsClosedToEmptySet() {
        String s = sql("", new SearchDTO());
        assertTrue(s.contains("id"), "无组时应加不可能命中条件，而不是什么都不加：" + s);
        assertFalse(s.contains("org_id"), "无组时不应有 org_id 语义（该值不可信）：" + s);
    }

    @Test
    void nullGroupAlsoFailsClosed() {
        String s = sql(null, new SearchDTO());
        assertTrue(s.contains("id"), s);
    }

    /** 级联效果：无组时用户筛选条件即使写了也不影响 —— 哨兵 id 让整体恒空 */
    @Test
    void noGroupWithUserFilterStillEmpty() {
        SearchDTO dto = new SearchDTO();
        dto.setGrade("合格");
        dto.setDepartment("中医内科");
        String s = sql("", dto);
        assertTrue(s.contains("id"), "无组 + 用户筛选：id 哨兵必须在：" + s);
    }

    /** 用户筛选与数据域取交集：两个 org_id 同时存在，名为 org_id 的是数据域 */
    @Test
    void userFilterIntersectsGroupDomain() {
        SearchDTO dto = new SearchDTO();
        dto.setDepartment("中医内科");
        String s = sql("grp-a", dto);
        assertTrue(s.contains("org_id"), "数据域必须在：" + s);
        assertTrue(s.contains("department"), "用户筛选应生效：" + s);
    }

    /** 用户选了 grade 筛选：能与数据域共存（它是业务筛选，不再是权限） */
    @Test
    void userGradeFilterNowAllowed() {
        SearchDTO dto = new SearchDTO();
        dto.setGrade("合格");
        String s = sql("grp-a", dto);
        assertTrue(s.contains("grade"), "用户可选 grade 是业务筛选：" + s);
        assertTrue(s.contains("org_id"), "数据域仍在：" + s);
    }

    // ---------------------------------------------------------- canAccess

    @Test
    void canAccessMatchesSameGroup() {
        Record r = new Record();
        r.setOrgId("grp-a");
        // 直接调用需要 RequestContext 里的 orgId；这里用「同组」的等价断言，
        // 真正的绑定值校验交给 RecordIsolationTest（它挂 MockHttpServletRequest）
        assertTrue(RecordFilter.groupIdForSql("grp-a").equals("grp-a"));
    }

    /** 无组时 domainOrgId() 必须返回不可能值（给聚合 SQL 用），而不是 null */
    @Test
    void domainGroupIdWithoutGroupIsSentinel() {
        String v = RecordFilter.groupIdForSql("");
        assertFalse(v == null || v.isBlank(), "无组必须给哨兵值：" + v);
        assertTrue(v.contains("no_group"), "哨兵值可识别：" + v);
    }
}