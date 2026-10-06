package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTaskVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
    private final ReviewWriteService reviewWriteService;

    /**
     * 分页查询复核任务列表，只读。
     *
     * <p>只取未失效任务（{@code is_obsolete=0}），按创建时间倒序；只返回本组织任务
     * 域，其余角色按传入状态过滤（兼容中文与英文状态名）。关联病历缺失的任务直接跳过，不占位。</p>
     *
     * @param page 页码，非法时取第 1 页
     * @param pageSize 每页条数，非法时取 20
     * @param status 状态筛选（可空，全部身份均生效）
     * @return 命中总数与任务列表项
     */
    @Override
    public ReviewTasksVO listTasks(Integer page, Integer pageSize, String status, Boolean overdueOnly) {
        // 1. 分页参数非法时回退为第 1 页 / 每页 20 条
        int p = page != null && page > 0 ? page : 1;
        int s = pageSize != null && pageSize > 0 ? pageSize : 20;

        // 2. 组装查询条件：未失效任务，按创建时间倒序
        QueryWrapper<ReviewTask> w = new QueryWrapper<>();
        w.eq("is_obsolete", 0);
        // 3. § 6.3 缺点 7：不能看到别组的复核任务（与判杂志事实同级的数据）。
        //    review_tasks 打组是写入时做的（upsertReviewTask），这里只需等值过滤。
        //    管理员「看全部」时不加这条 —— 与病历列表/详情同一口径（RecordFilter.canAccess
        //    已按 viewAllOrgs 放行），否则管理员能看到他组病历却看不到它的复核任务
        if (!RequestUtils.viewAllOrgs()) {
            w.eq("org_id", RequestUtils.currentOrgId());
        }
        // 4. 状态筛选：无组时上面的 org_id 等值已让结果为空，不再需要角色判断
        String dbStatus = dbStatus(status);
        if (dbStatus != null) {
            w.eq("status", dbStatus);
        }
        // 4.1 批次9：worklist「只需我处理」= 已超期且仍待复核的任务（最该先做的那批）。
        //     过滤必须落在 SQL 层：前端过滤只作用于当前页，用户会以为「我处理完了」而其它页还有
        //     超期任务 —— 那是状态撒谎。overdueOnly 优先于 status 入参（勾了它就是唯一口径）。
        if (Boolean.TRUE.equals(overdueOnly)) {
            w.eq("status", dbStatus("待复核"));
            w.lt("deadline_time", LocalDateTime.now());
        }
        w.orderByDesc("create_time");

        // 4. 分页查询
        Page<ReviewTask> pg = baseMapper.selectPage(new Page<>(p, s), w);
        // 5. 组装返回：总数与任务列表项（关联病历缺失的跳过，不占位）
        ReviewTasksVO vo = new ReviewTasksVO();
        vo.setTotal(pg.getTotal());
        // P5.2：关联病历已删（selectById 返回 null）的任务会跳过，计数后告知前端
        int skippedMissing = 0;
        for (ReviewTask t : pg.getRecords()) {
            Record r = recordMapper.selectById(t.getRecordId());
            if (r == null) {
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
     * 事务内的校验与三段写库见 {@link ReviewWriteService#review}。</p>
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
        return reviewWriteService.review(recordId, dto);
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
