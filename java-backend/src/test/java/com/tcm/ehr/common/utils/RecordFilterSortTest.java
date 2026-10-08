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
 * B3 用户可排序（方案 b）：白名单三列的排序段与 id 次级键方向。
 *
 * <p>锁四件事：① 注入串/未登记列一律回落默认序（不拼接、不报错）；
 * ② 三列各自的正反向段与 id 联动正确（{@code 列 desc → id ASC} / 反扫 {@code 列 asc → id DESC}，
 * registration_no 因索引升序而方向相反）；③ 默认序与旧实现逐字一致（向后兼容）；
 * ④ FiltersDTO 重载（批处理/范围删除走的入口）不受排序参数影响、默认序与 SearchDTO 入口相同。</p>
 */
@DisplayName("RecordFilter：B3 排序白名单与 id 次级键方向")
class RecordFilterSortTest {

    private static final String ORG = "org-sort-1";

    private static String orderOf(String segment) {
        int i = segment.indexOf("ORDER BY");
        return i < 0 ? "" : segment.substring(i);
    }

    private static String segment(SearchDTO dto) {
        return RecordFilter.build(ORG, dto).getSqlSegment();
    }

    @Test
    @DisplayName("未传排序 → 默认序与旧实现逐字一致（visit_time DESC, id ASC）")
    void defaultOrderUnchanged() {
        SearchDTO dto = new SearchDTO();
        dto.setDepartment("内科");
        String order = orderOf(segment(dto));
        assertTrue(order.contains("visit_time DESC") && order.contains("id ASC"),
                "默认序必须保持 visit_time DESC, id ASC：" + order);
        // FiltersDTO 重载（批处理/范围删除/统计下钻）同样默认序（两入口一致，审查报告 L8）
        String order2 = orderOf(RecordFilter.build(ORG, new FiltersDTO()).getSqlSegment());
        assertTrue(order2.contains("visit_time DESC") && order2.contains("id ASC"), order2);
    }

    @Test
    @DisplayName("白名单三列正反向：id 次级键与索引扫描方向一致")
    void whitelistedSortDirections() {
        SearchDTO desc = new SearchDTO();
        desc.setSortBy("score");
        String o1 = orderOf(segment(desc));
        assertTrue(o1.contains("score DESC") && o1.contains("id ASC"),
                "score 默认 desc → id ASC（正扫）：" + o1);

        SearchDTO asc = new SearchDTO();
        asc.setSortBy("score");
        asc.setSortOrder("asc");
        String o2 = orderOf(segment(asc));
        assertTrue(o2.contains("score ASC") && o2.contains("id DESC"),
                "score asc → id DESC（反扫，仍全序稳定）：" + o2);

        SearchDTO vt = new SearchDTO();
        vt.setSortBy("visitTime"); // 驼峰别名
        vt.setSortOrder("asc");
        String o3 = orderOf(segment(vt));
        assertTrue(o3.contains("visit_time ASC") && o3.contains("id DESC"),
                "visitTime(别名) asc → visit_time ASC, id DESC：" + o3);

        SearchDTO regno = new SearchDTO();
        regno.setSortBy("registration_no"); // 默认升序（该列索引为升序）
        String o4 = orderOf(segment(regno));
        assertTrue(o4.contains("registration_no ASC") && o4.contains("id ASC"),
                "registration_no 默认 asc → id ASC（升序索引正扫）：" + o4);

        SearchDTO regnoDesc = new SearchDTO();
        regnoDesc.setSortBy("registration_no");
        regnoDesc.setSortOrder("desc");
        String o5 = orderOf(segment(regnoDesc));
        assertTrue(o5.contains("registration_no DESC") && o5.contains("id DESC"),
                "registration_no desc → id DESC（反扫）：" + o5);
    }

    @Test
    @DisplayName("注入串/未登记列 → 回落默认序，且段里不含危险词")
    void injectionAndUnknownFallBackToDefault() {
        SearchDTO inject = new SearchDTO();
        inject.setSortBy("id);DROP TABLE records;--");
        String o1 = orderOf(segment(inject));
        assertTrue(o1.contains("visit_time DESC") && o1.contains("id ASC"),
                "注入串必须回落默认序：" + o1);
        assertFalse(o1.toUpperCase().contains("DROP"), "段里绝不能出现注入内容：" + o1);

        SearchDTO unknown = new SearchDTO();
        unknown.setSortBy("grade"); // grade 不在白名单
        unknown.setSortOrder("desc");
        String o2 = orderOf(segment(unknown));
        assertTrue(o2.contains("visit_time DESC") && o2.contains("id ASC"),
                "未登记列回落默认序：" + o2);

        SearchDTO badOrder = new SearchDTO();
        badOrder.setSortBy("score");
        badOrder.setSortOrder("zzz"); // 非法方向 → 取列默认（score 默认 desc）
        String o3 = orderOf(segment(badOrder));
        assertTrue(o3.contains("score DESC") && o3.contains("id ASC"),
                "非法方向回落列默认：" + o3);
    }
}