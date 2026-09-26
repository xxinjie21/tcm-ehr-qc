package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 病历公共过滤：**数据域（行级权限）→ 用户筛选** 顺序固定、取交集。
 *
 * <p>所有病历读取（查询 / 原始查看 / 后续图谱等）统一走本工具，禁止在业务方法中手写 where，
 * 避免口径漂移与"筛选条件绕过数据域"的越权。</p>
 *
 * <ul>
 * <li>管理员 = 全库；</li>
 * <li>审核员 = 复核域（{@code grade='待复核'}），再叠加用户筛选。</li>
 * </ul>
 */
public final class RecordFilter {

    public static final String ROLE_ADMIN = "管理员";
    public static final String ROLE_AUDITOR = "审核员";

    private RecordFilter() {
    }

    /** 构建查询条件：先数据域、后用户筛选 */
    public static QueryWrapper<Record> build(String role, SearchDTO dto) {
        QueryWrapper<Record> wrapper = new QueryWrapper<>();
        // 1. 数据域（行级权限）——必须先于用户筛选
        operatorScope(wrapper, role);
        // 2. 用户筛选（与数据域取交集，各条件之间取并集）
        if (dto != null) {
            if (notBlank(dto.getRegistrationNo())) {
                wrapper.like("registration_no", dto.getRegistrationNo().trim());
            }
            if (notBlank(dto.getOutpatientNo())) {
                wrapper.like("outpatient_no", dto.getOutpatientNo().trim());
            }
            if (notBlank(dto.getGender())) {
                wrapper.eq("gender", dto.getGender().trim());
            }
            if (notBlank(dto.getDepartment())) {
                wrapper.eq("department", dto.getDepartment().trim());
            }
            if (notBlank(dto.getDoctorId())) {
                wrapper.eq("doctor_id", dto.getDoctorId().trim());
            }
            if (notBlank(dto.getPattern())) {
                wrapper.like("pattern", dto.getPattern().trim());
            }
            if (notBlank(dto.getGrade())) {
                wrapper.eq("grade", dto.getGrade().trim());
            }
            if (notBlank(dto.getStatus())) {
                wrapper.eq("status", dto.getStatus().trim());
            }
            if (dto.getDateRange() != null && dto.getDateRange().size() == 2
                    && notBlank(dto.getDateRange().get(0)) && notBlank(dto.getDateRange().get(1))) {
                wrapper.ge("visit_time", dto.getDateRange().get(0).trim() + " 00:00:00");
                wrapper.le("visit_time", dto.getDateRange().get(1).trim() + " 23:59:59");
            }
        }
        // 排序键必须是列表实际展示的那一列（visit_time 接诊时间）。
        // 原先按 create_time 排，但批量导入/灌库场景下 create_time 会大量同值 ——
        // 实测 500 条演示数据 create_time 只有 1 个不同值，排序完全失效、返回 UUID 序，
        // 对用户等于随机。visit_time 有真实分布（2019~2025），且可走 idx_department_visit_time。
        wrapper.orderByDesc("visit_time");
        return wrapper;
    }

    /** 数据域（行级权限）：审核员强制待复核域；管理员全库 */
    private static void operatorScope(QueryWrapper<Record> wrapper, String role) {
        // 1. 管理员不限（null 就什么条件都不加）；审核员锁死待复核
        String scope = domainGrade(role);
        if (scope != null) {
            wrapper.eq("grade", scope);
        }
    }

    /**
     * 把「无类型 Map」形态的 filters 翻译成 {@link FiltersDTO}。
     *
     * <p>stats 契约与导出契约的 filters 都是 {@code Map<String,Object>}，字段名与
     * {@link FiltersDTO} 一致。有了这个翻译，那两处就不必各自手搓条件 —— 否则会多出
     * 第二套「范围组装」，排序键、数据域这类口径就跟着漂（实测就漂过一次：
     * 导出/预览自己 build wrapper，于是拿不到本工具的 ORDER BY）。</p>
     */
    public static FiltersDTO fromMap(Map<String, Object> filters) {
        // 1. 无 filters 给空 DTO（= 不限范围）
        FiltersDTO f = new FiltersDTO();
        if (filters == null) {
            return f;
        }
        // 2. 三个标量条件逐个翻译
        f.setDepartment(text(filters.get("department")));
        f.setPattern(text(filters.get("pattern")));
        f.setGrade(text(filters.get("grade")));
        // 3. 时间区间要两端都有才成立，缺一端当没给
        if (filters.get("dateRange") instanceof List<?> range && range.size() == 2
                && text(range.get(0)) != null && text(range.get(1)) != null) {
            f.setDateRange(List.of(text(range.get(0)), text(range.get(1))));
        }
        return f;
    }

    /**
     * 把范围条件拼成审计用的一行，给 {@code OperationLog.target} 用。
     *
     * <p>批量操作（数据清洗 / 数据集导出被拒 / 批量解析 / 批量重算）原来一律传 {@code null}，
     * 日志页「操作对象」列恒空，事后追责看不出那次操作动了哪个范围（审查报告 M4）。</p>
     *
     * @param filters {@link FiltersDTO}，或 stats 契约里的 {@code Map}（两种都要支持：
     * 导出走 Map、其余走 DTO）
     * @return 如「科室＝中医内科 · 分级＝待复核 · 2026-09-01 至 2026-09-30」；无条件时返回「全部病历」
     */
    public static String describe(Object filters) {
        String department = null;
        String grade = null;
        String pattern = null;
        String start = null;
        String end = null;
        // 1. 两种契约形态都要支持：DTO（列表/批量）与 Map（导出）
        if (filters instanceof FiltersDTO f) {
            department = f.getDepartment();
            grade = f.getGrade();
            pattern = f.getPattern();
            if (f.getDateRange() != null && f.getDateRange().size() == 2) {
                start = f.getDateRange().get(0);
                end = f.getDateRange().get(1);
            }
        } else if (filters instanceof Map<?, ?> m) {
            department = text(m.get("department"));
            grade = text(m.get("grade"));
            pattern = text(m.get("pattern"));
            if (m.get("dateRange") instanceof List<?> range && range.size() == 2) {
                start = text(range.get(0));
                end = text(range.get(1));
            }
        }

        // 2. 逐个条件拼一段，顺序固定便于事后比对两笔操作的范围
        List<String> parts = new ArrayList<>();
        if (notBlank(department)) {
            parts.add("科室＝" + department.trim());
        }
        if (notBlank(grade)) {
            parts.add("分级＝" + grade.trim());
        }
        if (notBlank(pattern)) {
            parts.add("证候＝" + pattern.trim());
        }
        // 3. 只给一端时写「不限」，别让审计看不出这是半区间
        if (notBlank(start) || notBlank(end)) {
            parts.add((notBlank(start) ? start.trim() : "不限") + " 至 " + (notBlank(end) ? end.trim() : "不限"));
        }
        return parts.isEmpty() ? "全部病历" : String.join(" · ", parts);
    }

    private static String text(Object o) {
        // 空值、空白、字面 "null" 一律归成 null（前端会传字符串 "null"）
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    /**
     * 数据域落在「分级」列上的取值：审核员 = {@code 待复核}；管理员 = {@code null}（不限）。
     *
     * <p>给 QueryWrapper 表达不了的聚合 SQL 用 —— Mapper 里以
     * {@code WHERE (#{grade} IS NULL OR grade = #{grade})} 落地。
     * 存在的意义是让「审核员的数据域是哪个分级」这个口径仍然只有一处，
     * 不在 Mapper 里再写一遍中文字面量。</p>
     */
    public static String domainGrade(String role) {
        return ROLE_AUDITOR.equals(role) ? "待复核" : null;
    }

    /** .1：按 filters{department,dateRange,pattern,grade} 构建（数据域→用户筛选） */
    public static QueryWrapper<Record> build(String role, FiltersDTO f) {
        QueryWrapper<Record> wrapper = new QueryWrapper<>();
        // 1. 数据域先叠加（必须最先，用户筛选只能在其上收窄）
        operatorScope(wrapper, role);
        // 2. 再拼用户筛选；f 为 null 表示不限
        if (f != null) {
            if (notBlank(f.getDepartment())) {
                wrapper.eq("department", f.getDepartment().trim());
            }
            if (notBlank(f.getPattern())) {
                wrapper.like("pattern", f.getPattern().trim());
            }
            if (notBlank(f.getGrade())) {
                wrapper.eq("grade", f.getGrade().trim());
            }
            // 3. 时间区间要两端齐全；补全时分秒，保证含首尾两天
            if (f.getDateRange() != null && f.getDateRange().size() == 2
                    && notBlank(f.getDateRange().get(0)) && notBlank(f.getDateRange().get(1))) {
                wrapper.ge("visit_time", f.getDateRange().get(0).trim() + " 00:00:00");
                wrapper.le("visit_time", f.getDateRange().get(1).trim() + " 23:59:59");
            }
        }
        // 与 SearchDTO 重载同一个排序键：本工具的两种入参不该给出不同顺序（审查报告 L8）。
        // 注意 FiltersDTO 重载的调用方多是聚合 / 批处理 / 按范围删除，排序对它们无意义，
        // 代价是这些查询多一次按 visit_time 的排序（3.5 万条量级需留意 deleteByFilter 与 clean）。
        wrapper.orderByDesc("visit_time");
        return wrapper;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
