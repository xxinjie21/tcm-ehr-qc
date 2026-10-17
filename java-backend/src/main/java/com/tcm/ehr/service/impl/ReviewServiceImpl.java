package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewStatsVO;
import com.tcm.ehr.domain.vo.ReviewTaskVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IReviewService;
import com.tcm.ehr.service.IReviewWriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 复核服务实现。
 *
 * <ul>
 * <li>列表：review_tasks 过滤 is_obsolete=0，并按组织数据域过滤；</li>
 * <li>复核：合并人工修正 → QcScorer 自动重算 → 合格/无效则任务完成，仍待复核则任务保持并更新分数；</li>
 * <li>超时仅计算属性（ReviewTaskUtil），无后台定时任务、不自动流转。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements IReviewService {

    private final RecordMapper recordMapper;
    private final EntityNormalizer entityNormalizer;
    private final IReviewWriteService reviewWriteService;

    /**
     * 组装复核任务查询条件（未失效 + 数据域 + 状态 / 超期 + 范围），列表与统计共用。
     *
     * <p>§6.3 缺点 7：不能看到别组的复核任务 —— 管理员「看全部」时不加 org 条件
     * （与病历列表/详情同一口径，RecordFilter.canAccess 已按 viewAllOrgs 放行）。</p>
     *
     * @param scope 范围过滤（科室 / 接诊时间 / 导入时间）；可空表示不限
     */
    private QueryWrapper<ReviewTask> buildTaskWrapper(String status, Boolean overdueOnly,
                                                      FiltersDTO scope) {
        QueryWrapper<ReviewTask> w = new QueryWrapper<>();
        w.eq("is_obsolete", 0);
        if (!RequestUtils.viewAllOrgs()) {
            w.eq("org_id", RequestUtils.currentOrgId());
        }
        String dbStatus = dbStatus(status);
        if (dbStatus != null) {
            w.eq("status", dbStatus);
        }
        // 批次9：worklist「只需我处理」= 已超期且仍待复核的任务（最该先做的那批）。
        // 过滤必须落在 SQL 层：前端过滤只作用于当前页。overdueOnly 优先于 status 入参。
        if (Boolean.TRUE.equals(overdueOnly)) {
            w.eq("status", dbStatus("待复核"));
            w.lt("deadline_time", LocalDateTime.now());
        }
        applyRecordScope(w, scope);
        return w;
    }

    /**
     * 把范围过滤落到<b>关联病历</b>上：用 {@code record_id IN (SELECT id FROM records WHERE …)}
     * 收窄任务集合，列表与统计共用同一段组装。
     *
     * <p>为什么是子查询而不是 JOIN：{@code review_tasks} 本身没有科室 / 接诊时间 /
     * 导入时间列（表见 {@code ReviewTask}），任务的范围只能由关联病历的字段定义；
     * 而 {@code listTasks} 是高频路径，MyBatis-Plus 的 {@code QueryWrapper} 不便 JOIN，
     * 子查询让「范围」与「状态 / 数据域」仍在同一个 wrapper 里，口径只有一处。</p>
     *
     * <p><b>子查询里不再叠加 org 条件</b>：数据域由 {@code review_tasks.org_id} 那层
     * 负责（管理员看全部时不加），在子查询里再按 {@code records.org_id} 过滤会把
     * {@code viewAllOrgs} 旁路抵消掉 —— 管理员就会漏看别组导入病历产生的任务。</p>
     *
     * <p>时间口径与 {@code RecordFilter.applyRange} 逐字相同：两端齐全才生效，
     * 补 00:00:00 / 23:59:59 保证含首尾两天；department 经 {@code apply} 的
     * {@code {n}} 占位符走参数绑定，不拼字符串（用户输入）。</p>
     */
    private void applyRecordScope(QueryWrapper<ReviewTask> w, FiltersDTO scope) {
        if (scope == null) {
            return;
        }
        List<String> conds = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (!TextUtil.isBlank(scope.getDepartment())) {
            conds.add("department = {" + args.size() + "}");
            args.add(scope.getDepartment().trim());
        }
        appendRangeCond(conds, args, "visit_time", scope.getDateRange());
        appendRangeCond(conds, args, "create_time", scope.getImportRange());
        if (conds.isEmpty()) {
            return;
        }
        w.apply("record_id IN (SELECT id FROM records WHERE "
                + String.join(" AND ", conds) + ")", args.toArray());
    }

    /** 时间区间条件：两端齐全才生效（缺一端当没给），补全时分秒保证含首尾两天 */
    private void appendRangeCond(List<String> conds, List<Object> args, String column,
                                 List<String> range) {
        if (range == null || range.size() != 2) {
            return;
        }
        String start = range.get(0);
        String end = range.get(1);
        if (TextUtil.isBlank(start) || TextUtil.isBlank(end)) {
            return;
        }
        conds.add(column + " >= {" + args.size() + "}");
        args.add(start.trim() + " 00:00:00");
        conds.add(column + " <= {" + args.size() + "}");
        args.add(end.trim() + " 23:59:59");
    }

    /** 复核概览统计：待复核 / 已完成 / 待复核超期（性能审查 P1-5，只 COUNT 不 SELECT） */
    @Override
    public ReviewStatsVO countStats(FiltersDTO scope) {
        ReviewStatsVO vo = new ReviewStatsVO();
        vo.setPending(countOf("待复核", false, scope));
        vo.setDone(countOf("已完成", false, scope));
        vo.setOverdue(countOf("待复核", true, scope));
        return vo;
    }

    private long countOf(String status, Boolean overdueOnly, FiltersDTO scope) {
        Long n = baseMapper.selectCount(buildTaskWrapper(status, overdueOnly, scope));
        return n == null ? 0 : n;
    }

    /**
     * 分页查询复核任务列表，只读。
     *
     * <p>只取未失效任务（{@code is_obsolete=0}），按创建时间倒序；只返回本组织任务
     * 域，其余角色按传入状态过滤（兼容中文与英文状态名）。关联病历缺失的任务直接跳过，不占位。
     * 范围条件（科室 / 接诊时间 / 导入时间）经关联病历子查询收窄，与统计同一口径。</p>
     *
     * @param page 页码，非法时取第 1 页
     * @param pageSize 每页条数，非法时取 20
     * @param status 状态筛选（可空，全部身份均生效）
     * @param overdueOnly 批次9：true 时只返回已超期且仍待复核的任务
     * @param scope 范围过滤，可空表示不限
     * @return 命中总数与任务列表项
     */
    @Override
    public ReviewTasksVO listTasks(Integer page, Integer pageSize, String status, Boolean overdueOnly,
                                   FiltersDTO scope) {
        // 1. 分页参数非法时回退为第 1 页 / 每页 20 条
        int p = page != null && page > 0 ? page : 1;
        int s = pageSize != null && pageSize > 0 ? pageSize : 20;

        // 2. 组装查询条件：未失效任务，按创建时间倒序
        QueryWrapper<ReviewTask> w = buildTaskWrapper(status, overdueOnly, scope);
        w.orderByDesc("create_time");

        // 4. 分页查询
        Page<ReviewTask> pg = baseMapper.selectPage(new Page<>(p, s), w);
        // 5. 组装返回：总数与任务列表项（关联病历缺失的跳过，不占位）
        ReviewTasksVO vo = new ReviewTasksVO();
        vo.setTotal(pg.getTotal());
        // P5.2：关联病历已删的任务会跳过，计数后告知前端。原先每行 selectById 是 N+1
        // （每页 20 条 → 21 次查询，且整行拉回 structured_data 大字段）；改为一次批量只取 id 集合。
        Set<String> existingIds = existingRecordIds(pg.getRecords());
        int skippedMissing = 0;
        for (ReviewTask t : pg.getRecords()) {
            if (!existingIds.contains(t.getRecordId())) {
                skippedMissing++;
                continue;
            }
            ReviewTaskVO rv = new ReviewTaskVO();
            rv.setTaskId(t.getId());
            rv.setRecordId(t.getRecordId());
            rv.setStatus("completed".equals(t.getStatus()) ? "已完成" : "待复核");
            rv.setIssueType(t.getIssueType());
            rv.setScore(t.getScore());
            rv.setCreateTime(t.getCreateTime());
            rv.setDeadlineTime(t.getDeadlineTime());
            rv.setOverdue(ReviewTaskUtil.isOverdue(t.getDeadlineTime()));
            // P5.3：列表不再内联整份 structuredData（每行一份很胖），详情由 /raw/按需拉；
            // 前端原本就以详情为准，列表份只是“列表数据可能滞后”的兜底，去掉不影响正确性
            vo.getTasks().add(rv);
        }
        vo.setSkippedMissing(skippedMissing);
        return vo;
    }

    /**
     * 人工复核入口（批次 26.4 / 26.e 定案 A）。
     *
     * <p>归一先于事务：{@code normalizeMap} 要走 ES，若和写库同处一个 {@code @Transactional}，
     * ES 一慢就会一直占着数据库连接。这里在事务外归一，失败即抛（ES 不可用 → 503）
     * 且一个字节都没写 —— 与原先版本「整单回滚」的用户可见语义一致。
     * 事务内的校验与三段写库见 {@link IReviewWriteService#review}。</p>
     *
     * <p>归一是为了复核员新输入的词带上 {@code normLevel}：不加的话
     * {@code QcScorer.countUnnormalized} 会把「无 normLevel」算作未标准化，
     * 结果是人工修正反而扣分。</p>
     */
    @Override
    public ReviewResultVO review(String recordId, ReviewDTO dto) {
        if (dto != null && dto.getCorrectedData() != null) {
            entityNormalizer.normalizeMap(dto.getCorrectedData(), RequestUtils.currentOrgId());
        }
        ReviewResultVO vo = reviewWriteService.review(recordId, dto);
        // 复核会改写 correctedData（结构化数据）→ 统计词频过期，主动失效（B1）
        com.tcm.ehr.common.cache.StatsCacheInvalidator.invalidateStats();
        return vo;
    }

    /** 批量查询这些任务关联的病历中仍存在的 id 集合（只取 id 列，不拉 structured_data 大字段）。 */
    private Set<String> existingRecordIds(List<ReviewTask> tasks) {
        List<String> ids = tasks.stream()
                .map(ReviewTask::getRecordId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (Object o : recordMapper.selectObjs(
                new QueryWrapper<Record>().select("id").in("id", ids))) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private String dbStatus(String status) {
        // 1. 空状态给 null，调用侧保持原样
        if (status == null || status.isBlank()) {
            return null;
        }
        // 2. 中英文都收：页面传中文、历史数据是英文，两种都要归一到库里的值
        return switch (status) {
            case "待复核", "pending" -> "pending";
            case "已完成", "completed" -> "completed";
            default -> status;
        };
    }
}
