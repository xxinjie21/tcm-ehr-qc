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
 * <p><b>数据域 = 课题组（阶段 2）</b>：原本用 {@code grade}（质控结论）当权限维度，现已改为
 * {@code org_id = 当前用户的主组}。{@code grade} 从此只做业务筛选（供应商可选），
 * 不再影响任何一个人能看到哪些数据。</p>
 *
 * <p>原设计的两个后果正是本次改造的动机：复核通过一份病历后会
 * <b>立刻失去访问它的权限</b>；一次批量重算会<b>批量翻转可见性</b>。</p>
 *
 * <p>⚠️ <b>fail-closed 是本类最关键的一行</b>：{@code orgId} 为空时必须生成<b>返回空集</b>的查询，
 * 而不能不加条件 —— 不加条件 = 看到全库 = 最严重的越权。</p>
 */
public final class RecordFilter {

    /**
     * fail-closed 用的不可能值、与 {@code records.id} 的 UUID 不可能相等。
     * 它不是"这个 id 不存在"的东西，而是一个<b>不可能被任何导入数据命中</b>的值。
     */
    private static final String NO_GROUP_SENTINEL = "\u0000__no_group__";

    private RecordFilter() {
    }

    /**
     * 按主组取数据域上下文（就是 {@link #currentOrgId()} 的别名）。
     *
     * <p>为什么不直接用 {@code RequestUtils.currentOrgId()}：这里需要能被单测直接接窗口
     * 动态传入 orgId（{@code RecordIsolationTest} 就是这么做的），而不是固定读线程上下文。</p>
     */
    public static String currentOrgId() {
        return RequestUtils.currentOrgId();
    }

    /**
     * 构建查询条件：先数据域、后用户筛选。
     *
     * <p>管理员「看全部」（{@link RequestUtils#viewAllOrgs()}）时<b>不加组织条件</b>。</p>
     */
    public static QueryWrapper<Record> build(String orgId, SearchDTO dto) {
        QueryWrapper<Record> wrapper = new QueryWrapper<>();
        // 1. 数据域（行级权限）——必须先于用户筛选
        operatorScope(wrapper, orgId, RequestUtils.viewAllOrgs());

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
                patternUnion(wrapper, dto.getPattern().trim());
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
        // 对用户等于随机。visit_time 有真实分布（2019~2025），可走 idx_department_visit_time。
        // 次级键 id 不能省：visit_time 大量同值时，LIMIT/OFFSET 分页在页边界会重复取或漏取
        // （批任务据此累加分级计数，重复取到的还会被批内判重扣分）
        //
        // B3 用户可排序（方案 b）：白名单三列，未命中/未传一律回落上面对的默认序。
        // 注入串（sortBy=id); DROP...）会被白名单挡掉 → 走默认序，不拼接、不报错。
        String sortCol = sortColumn(dto == null ? null : dto.getSortBy());
        if (sortCol == null) {
            wrapper.orderByDesc("visit_time").orderByAsc("id");
        } else {
            String so = dto.getSortOrder() == null ? "" : dto.getSortOrder().trim();
            // 方向：只认 asc / desc，其余（含空、含非法值）取该列默认方向
            boolean desc = "asc".equalsIgnoreCase(so) ? false
                    : ("desc".equalsIgnoreCase(so) ? true : !"registration_no".equals(sortCol));
            // 次级键 id 与**索引扫描方向**一致（性能审查方案 B 同构）：
            //   score / visit_time 走 (org_id, <col> DESC, id ASC) 索引 →
            //   列 desc 正扫 = id ASC；列 asc 反扫 = id DESC。
            //   registration_no 走 (org_id, registration_no, id) 升序索引 →
            //   列 asc 正扫 = id ASC；列 desc 反扫 = id DESC。
            // 无论哪条路径，(列, id) 都是严格全序 —— 分页页边界不重不漏。
            boolean idAsc = "registration_no".equals(sortCol) ? !desc : desc;
            // ⚠️ 显式用 orderByDesc/orderByAsc 而非 orderBy(boolean, String)：
            // MP 里后者是 orderBy(boolean condition, R... columns) 的 varargs 重载，
            // boolean 会被当成 condition —— 传 false 时排序会整体不生效。
            if (desc) {
                wrapper.orderByDesc(sortCol);
            } else {
                wrapper.orderByAsc(sortCol);
            }
            if (idAsc) {
                wrapper.orderByAsc("id");
            } else {
                wrapper.orderByDesc("id");
            }
        }
        return wrapper;
    }

    /**
     * 排序列白名单（B3）：key=入参（含驼峰别名），value=真实列名。
     * 只认这三列 —— 其余一律返回 null（回落默认序）。新增可排序列时先补对应
     * {@code (org_id, <col> [方向], id)} 复合索引，再加这里。
     */
    private static final java.util.Map<String, String> SORT_WHITELIST = java.util.Map.of(
            "score", "score",
            "visit_time", "visit_time",
            "visitTime", "visit_time",
            "registration_no", "registration_no",
            "registrationNo", "registration_no");

    private static String sortColumn(String sortBy) {
        if (sortBy == null) {
            return null;
        }
        return SORT_WHITELIST.get(sortBy.trim());
    }

    /**
     * 数据域（行级权限）：限当前主组；无组时<b>返回空集</b>。
     *
     * <p>⚠️ 这里不得写成"无组就不加条件"——那等于看到全库，
     * 是本次改造中最严重的越权。它也是防止写退出一个不可能的 id：
     * 以后若有人导入了它，无组用户就会看到那一条。</p>
     */
    private static void operatorScope(QueryWrapper<Record> wrapper, String orgId, boolean viewAllOrgs) {
        // 0. 管理员看全部：明确「不加组织条件」。
        //    ⚠️ 这里必须靠独立的 viewAllOrgs 标记判断，**不能**写成
        //    「orgId 为空就不加条件」—— 批次 4 之后 orgId 为空是 fail-closed
        //    （查不到任何数据），那样会让「看全部」变成「什么都看不到」，
        //    是个方向相反、很难一眼看出的 bug。
        if (viewAllOrgs) {
            return;
        }
        if (orgId == null || orgId.isBlank()) {
            wrapper.eq("id", NO_GROUP_SENTINEL);
            return;
        }
        wrapper.eq("org_id", orgId.trim());
    }

    /**
     * 查询前的越权自检：当前用户是否能看这条病历。
     *
     * <p>用于 {@code selectById} 类“查后校验”的路径（它们不能用 wrapper）。</p>
     *
     * @return true = 可访问；false = 不可访问（调用方应返回 404，不泄露"存在但看不到"）
     */
    public static boolean canAccess(Record record) {
        if (record == null) {
            return false;
        }
        // 管理员「看全部」时不加组织条件，与 operatorScope 同一口径 ——
        // 少了这一条，管理员在列表里看得到他组病历、点开详情却是 404（三处口径互斥）
        if (RequestUtils.viewAllOrgs()) {
            return record.getOrgId() != null;
        }
        String orgId = currentOrgId();
        // 无组 → 一条也看不到（与 operatorScope 同一口径）
        if (orgId == null || orgId.isBlank()) {
            return false;
        }
        // 记录本身就无组（迁移未执行）→ 也看不到
        String recordOrgId = record.getOrgId();
        return recordOrgId != null && orgId.equals(recordOrgId.trim());
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
        FiltersDTO filterDto = new FiltersDTO();
        if (filters == null) {
            return filterDto;
        }
        // 2. 三个标量条件逐个翻译
        filterDto.setDepartment(text(filters.get("department")));
        filterDto.setPattern(text(filters.get("pattern")));
        filterDto.setGrade(text(filters.get("grade")));
        // 3. 时间区间要两端都有才成立，缺一端当没给
        if (filters.get("dateRange") instanceof List<?> dateRange && dateRange.size() == 2
                && text(dateRange.get(0)) != null && text(dateRange.get(1)) != null) {
            filterDto.setDateRange(List.of(text(dateRange.get(0)), text(dateRange.get(1))));
        }
        return filterDto;
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
        if (filters instanceof FiltersDTO filterDto) {
            department = filterDto.getDepartment();
            grade = filterDto.getGrade();
            pattern = filterDto.getPattern();
            if (filterDto.getDateRange() != null && filterDto.getDateRange().size() == 2) {
                start = filterDto.getDateRange().get(0);
                end = filterDto.getDateRange().get(1);
            }
        } else if (filters instanceof Map<?, ?> rawFilters) {
            department = text(rawFilters.get("department"));
            grade = text(rawFilters.get("grade"));
            pattern = text(rawFilters.get("pattern"));
            if (rawFilters.get("dateRange") instanceof List<?> dateRange && dateRange.size() == 2) {
                start = text(dateRange.get(0));
                end = text(dateRange.get(1));
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
        // 1. 空值直接返回 2. 空白与字面 "null" 归成 null（前端会传字符串 "null"）
        if (o == null) {
            return null;
        }
        String trimmed = String.valueOf(o).trim();
        return trimmed.isEmpty() || "null".equals(trimmed) ? null : trimmed;
    }

    /**
     * 数据域落在 {@code org_id} 列上的取值（即当前主组）。
     *
     * <p>给 QueryWrapper 表达不了的聚合 SQL 用 —— Mapper 里以
     * {@code WHERE (#{orgId} IS NULL OR org_id = #{orgId})} 落地。
     * 存在的意义是让「数据域是哪个组」这个口径仍然只有一处，
     * 不在 Mapper 里再写一遍。</p>
     *
     * <p>为何取值不能简单地是去掉空值：无组用户必须看不到任何数据，
     * 而 {@code WHERE #{orgId} IS NULL OR ...} 的语义是「无组 → 不限」——恰好相反。
     * 故这里返回不可能值，让 SQL 自然落到空集。</p>
     */
    public static String domainOrgId() {
        return groupIdForSql(currentOrgId());
    }

    /**
     * 把 orgId 转成可以直接交给聚合 SQL 的值：无组 → 不可能值。
     *
     * @param orgId 当前主组，可为 null / 空串
     * @return 非空的 orgId，或 {@link #NO_GROUP_SENTINEL}
     */
    public static String groupIdForSql(String orgId) {
        return (orgId == null || orgId.isBlank()) ? NO_GROUP_SENTINEL : orgId.trim();
    }

    /** .1：按 filters{department,dateRange,pattern,grade} 构建（数据域→用户筛选） */
    public static QueryWrapper<Record> build(String orgId, FiltersDTO filterDto) {
        return build(orgId, filterDto, RequestUtils.viewAllOrgs(), true);
    }

    /**
     * 聚合专用：只出 WHERE 口径，**不追加排序键**。
     *
     * <p>为什么单独开一个入口：看板词频已下沉到 SQL 侧（{@code JSON_TABLE} + {@code GROUP BY}，
     * 性能审查 P0-3#3），而 {@code ORDER BY} 与 {@code GROUP BY} 在同一层不能共存 ——
     * 排序条件留在 wrapper 里会让 {@code ${ew.customSqlSegment}} 拼出非法 SQL。</p>
     *
     * <p>筛选口径仍然只有这一处：本方法与 {@link #build(String, FiltersDTO)} 共用同一段组装代码，
     * 只在最后一步不追加排序键。聚合结果本就没有「行顺序」的概念，去掉它不改变任何口径。</p>
     *
     * @param orgId     当前机构
     * @param filterDto 用户筛选，可为 null（表示不限）
     * @return 只含数据域 + 用户筛选、**无排序**的查询条件
     */
    public static QueryWrapper<Record> buildForAggregate(String orgId, FiltersDTO filterDto) {
        return build(orgId, filterDto, RequestUtils.viewAllOrgs(), false);
    }

    /**
     * 按「显式机构」构建，不做「管理员看全部」的旁路。
     *
     * 给批任务的提交侧预统计用。批任务在提交线程把计划条数算进 total，worker 在后台线程
     * 按 qc_task/nlp_task 的 org_id 快照重建条件 —— 后台线程里 viewAllOrgs() 恒为 false，
     * 于是管理员提交时若用 build() 计数，total 会是全库而实际只跑本组，进度与分级汇总
     * 的分母就对不上（2026-10-05 修）。
     *
     * 这里显式按机构限定，让「计划条数」与「实际执行范围」同源；语义与
     * {@link #build(String, FiltersDTO)} 在 viewAllOrgs=false 时完全一致。
     *
     * @param orgId 机构号；为空时按 fail-closed 返回空集（不放行全库）
     * @param filterDto     用户筛选，可为 null
     * @return 只含该机构、且已含用户筛选与排序键的查询条件
     */
    public static QueryWrapper<Record> buildWithinOrg(String orgId, FiltersDTO filterDto) {
        return build(orgId, filterDto, false, true);
    }

    /**
     * 数据域 → 用户筛选 的共用实现；viewAllOrgs 与是否追加排序键由调用方显式给定。
     *
     * @param withOrder 是否追加排序键。列表 / 导出 / 批处理 / 范围删除都要稳定的行顺序，
     *                  传 true；只有 SQL 侧聚合（{@link #buildForAggregate}）传 false ——
     *                  {@code ORDER BY} 与 {@code GROUP BY} 不能共存。
     */
    private static QueryWrapper<Record> build(String orgId, FiltersDTO filterDto, boolean viewAllOrgs,
                                              boolean withOrder) {
        QueryWrapper<Record> wrapper = new QueryWrapper<>();
        // 1. 数据域先叠加（必须最先，用户筛选只能在其上收窄）
        operatorScope(wrapper, orgId, viewAllOrgs);
        // 2. 再拼用户筛选；filterDto 为 null 表示不限
        if (filterDto != null) {
            if (notBlank(filterDto.getDepartment())) {
                wrapper.eq("department", filterDto.getDepartment().trim());
            }
            if (notBlank(filterDto.getPattern())) {
                patternUnion(wrapper, filterDto.getPattern().trim());
            }
            if (notBlank(filterDto.getGrade())) {
                wrapper.eq("grade", filterDto.getGrade().trim());
            }
            // 3. 时间区间要两端齐全；补全时分秒，保证含首尾两天
            if (filterDto.getDateRange() != null && filterDto.getDateRange().size() == 2
                    && notBlank(filterDto.getDateRange().get(0)) && notBlank(filterDto.getDateRange().get(1))) {
                wrapper.ge("visit_time", filterDto.getDateRange().get(0).trim() + " 00:00:00");
                wrapper.le("visit_time", filterDto.getDateRange().get(1).trim() + " 23:59:59");
            }
        }
        // 与 SearchDTO 重载同一个排序键：本工具的两种入参不该给出不同顺序（审查报告 L8）。
        // 注意 FiltersDTO 重载的调用方多是聚合 / 批处理 / 按范围删除，排序对它们无意义，
        // 代价是这些查询多一次按 visit_time 的排序（3.5 万条量级需留意 deleteByFilter 与 clean）。
        // 次级键 id 与 SearchDTO 重载同一理由：分页要稳定
        // withOrder=false 只出现在 buildForAggregate（SQL 侧聚合，GROUP BY 与 ORDER BY 互斥）
        if (withOrder) {
            wrapper.orderByDesc("visit_time").orderByAsc("id");
        }
        return wrapper;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 证候筛选统一口径（A8）：取「原始 {@code pattern} 列 <b>OR</b> {@code structured_data}
     * JSON」的<b>并集</b>模糊匹配。
     *
     * <p>为什么必须并集：医生在证候输入框可能填归一前（原写法）或归一后（标准词）任意一种，
     * 只有 {@code pattern LIKE} 会漏掉「仅写在 structured_data.patternList 里的标准词」（归一后
     * 的证候写法常与医生原文不同）。过去列表/范围删除/统计下钻只认原始列、导出才认 JSON ——
     * 同一筛选条件两边结果集不一致（实测筛「气滞痰阻证」列表 0 条、导出 50 条）。</p>
     *
     * <p>医疗数据「漏」比「多」严重：宁可多命中几条，也不能让医生在列表里搜不到标准的归一结果。
     * {@code structured_data} 是 JSON 列，但 MySQL 的 {@code LIKE} 在其上完全可用
     * （中文正常命中，无需 unicode 转义序列），实测 4 万行仅比单列 LIKE 多约 60% 耗时（低频操作）。</p>
     *
     * @param wrapper 已带数据域与其它筛选的 wrapper
     * @param value   用户输入的证候词（like 子串）
     */
    private static void patternUnion(QueryWrapper<Record> wrapper, String value) {
        wrapper.and(w -> w.like("pattern", value).or().like("structured_data", value));
    }
}
