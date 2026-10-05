package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ReviewTaskVO;
import com.tcm.ehr.domain.vo.ReviewTasksVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 复核服务实现。
 *
 * <ul>
 * <li>列表：review_tasks 过滤 is_obsolete=0，并按组织数据域过滤；</li>
 * <li>复核：合并人工修正 → QcScorer 自动重算 → 合格/无效则任务完成，仍待复核则任务保持并更新分数；</li>
 * <li>超时仅计算属性（ReviewTaskUtil），无后台定时任务、不自动流转。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl extends ServiceImpl<ReviewTaskMapper, ReviewTask> implements IReviewService {

    private final RecordMapper recordMapper;
    private final ObjectMapper objectMapper;
    private final com.tcm.ehr.common.config.QcRuleStore qcRuleStore;
    private final EntityNormalizer entityNormalizer;

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
     * 人工复核：校正数据 → 重算评分 → 复核任务流转。
     *
     * <p>三段写库必须同生共死，故加事务；否则中途失败会出现"分数已回写、任务仍 pending"的对不上。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ReviewResultVO review(String recordId, ReviewDTO dto) {
        Record r = recordMapper.selectById(recordId);
        if (r == null) {
            throw new ResourceNotFoundException(1006, "病历不存在");
        }
        // § 6.3 缺点 8：不能复核别组病历。写操作用 ForbiddenException
        if (!RecordFilter.canAccess(r)) {
            throw new ForbiddenException("无权复核该病历");
        }
        // 已隔离（无效）的病历不许复核：复核会用当前数据重算，把「无效」翻回「合格」，
        // 等于一道越权之外的旁路把隔离结论撤销掉。隔离是终态，要改只能重新导入 / 清洗
        if ("无效".equals(r.getGrade())) {
            throw new ForbiddenException("该病历已被隔离（无效），不能复核");
        }

        List<ReviewTask> tasks = baseMapper.selectList(new QueryWrapper<ReviewTask>()
                .eq("record_id", recordId).eq("is_obsolete", 0).eq("status", "pending"));
        if (tasks.isEmpty()) {
            // 统一抛 ResourceNotFoundException：返回 null 会让 Controller 再造一个错误码，
            // 同一条"资源不存在"就有 1006/2003 两个说法，且 data 为 null
            throw new ResourceNotFoundException(1006, "复核记录不存在或状态已完结");
        }
        ReviewTask task = tasks.get(0);

        // 1. 人工修正（可选）：合并 correctedData 回写 structured_data
        if (dto != null && dto.getCorrectedData() != null) {
            // 1.1 先跑一遍归一：复核员新输入的词只带 content/name、没有 normLevel，
            //     而 QcScorer.countUnnormalized 把「无 normLevel」算作未标准化 ——
            //     结果是**人工修正反而扣分**。这里在写回前用与模型抽取相同的口径归一。
            Map<String, Object> corrected = dto.getCorrectedData();
            entityNormalizer.normalizeMap(corrected, RequestUtils.currentOrgId());

            String json;
            try {
                json = objectMapper.writeValueAsString(corrected);
            } catch (JacksonException e) {
                throw new IllegalArgumentException("修正数据格式错误");
            }
            // 1.2 打「人工修改」标记（由后端写，前端传不进来 —— 否则这个标记可自报，就失去意义）。
            //     原来这里直接写回、不打点：前端 buildCorrected 只组装 9 个实体键、不含 _meta，
            //     于是旧 _meta 连同「依据哪一版词典」的溯源信息一起丢失。
            json = StructuredDataMeta.stampManual(objectMapper, json,
                    RequestUtils.currentUsername());
            recordMapper.updateStructuredData(recordId, json);
            r.setStructuredData(json);
        }

        // 2. 自动重算（判定地基仍是规则）
        //    规则必须按当前组织取：用进程内基线 get() 会与质控页的 getFor(组织) 分叉，
        //    表现为「同一条病历复核后的分级与质控页不一致」。
        //    「重复」标志从上一次落库的评分结果里读回 —— 原来恒传 false，等于复核一次
        //    就把批量扣掉的「重复数据」-5 分抹掉（85 分的重复病历一点通过就变 90 分合格）
        ScoreResultVO sr = QcScorer.score(asMap(r.getStructuredData()), r,
                duplicateFromQcResults(r.getQcResults()),
                qcRuleStore.getFor(RequestUtils.currentOrgId()));
        String status = switch (sr.getGrade()) {
            case "合格" -> "completed";
            case "待复核" -> "reviewing";
            default -> "invalid";
        };
        try {
            recordMapper.updateScoreFields(recordId, sr.getScore(), sr.getGrade(), status,
                    objectMapper.writeValueAsString(sr));
        } catch (JacksonException e) {
            throw new IllegalStateException("评分结果写入失败", e);
        }

        // 3. 任务流转：仍待复核 → 保持 pending 并刷新；判为无效 → 与批量路径同一口径作废；否则完成
        ReviewResultVO result = new ReviewResultVO();
        result.setScore(sr.getScore());
        if ("待复核".equals(sr.getGrade())) {
            task.setStatus("pending");
            task.setScore(sr.getScore());
            task.setIssueType(issueType(sr));
            baseMapper.updateById(task);
            result.setStatus("待复核");
        } else if ("无效".equals(sr.getGrade())) {
            // 批量路径判无效时写 obsolete/is_obsolete=1，复核路径原来写 completed ——
            // 同一结论两条路径两种终态，按任务表统计复核工作量时口径不可比
            task.setScore(sr.getScore());
            task.setStatus("obsolete");
            task.setIsObsolete(1);
            task.setReviewedBy(RequestUtils.currentUsername());
            task.setCompletedTime(LocalDateTime.now().withNano(0));
            baseMapper.updateById(task);
            result.setStatus("无效");
        } else {
            task.setStatus("completed");
            task.setScore(sr.getScore());
            task.setReviewedBy(RequestUtils.currentUsername());
            task.setCompletedTime(LocalDateTime.now().withNano(0));
            baseMapper.updateById(task);
            result.setStatus("已完成");
        }
        for (ScoreResultVO.Deduction d : sr.getDeductions()) {
            result.getErrors().add(new ReviewResultVO.ErrorItem(d.getType(), d.getReason()));
        }
        for (String c : sr.getLogicConflicts()) {
            result.getErrors().add(new ReviewResultVO.ErrorItem("逻辑冲突", c));
        }
        log.info("[复核] 病历 {} 复核完成：{}（{} 分）", recordId, result.getStatus(), sr.getScore());
        return result;
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

    private String issueType(ScoreResultVO vo) {
        // 1. 从重到轻取第一项：逻辑冲突 > 缺失字段 > 评分不达标
        if (!vo.getLogicConflicts().isEmpty()) {
            return "逻辑冲突";
        }
        if (vo.getDeductions().stream().anyMatch(d -> "核心字段缺失".equals(d.getType()))) {
            return "缺失字段";
        }
        return "评分不达标";
    }

    /**
     * 复核重算用的「重复」标志：从上一次落库的评分结果里读回。
     *
     * 复核曾恒传 false —— 批量按批内哈希扣掉的「重复数据」-5 分会凭空回来，
     * 同一条病历的分数取决于走哪个入口，不可复现。判定必须与上次一致。
     *
     * 解析不了就当不重复：宁可少扣分，也不要因为一条脏 qc_results 让复核失败。
     */
    private boolean duplicateFromQcResults(String qcResults) {
        if (qcResults == null || qcResults.isBlank()) {
            return false;
        }
        try {
            ScoreResultVO prev = objectMapper.readValue(qcResults, ScoreResultVO.class);
            return prev.getDeductions() != null && prev.getDeductions().stream()
                    .anyMatch(d -> "重复数据".equals(d.getType()));
        } catch (Exception e) {
            log.warn("[复核] qc_results 解析失败，重复标志按 false 处理：{}", e.getMessage());
            return false;
        }
    }

    /** 解析结构化数据；空或坏 JSON 返回空 map（复核时按"未结构化"继续，不中断） */
    private Map<String, Object> asMap(String json) {
        // 1. 空值给 null
        if (json == null || json.isBlank()) {
            return null;
        }
        // 2. 坏 JSON 也给 null：复核要能继续走完，不能因为一条脏数据卡住复核员。
        //    但必须留痕：null 在下游表示「没有结构化数据」，
        //    与「数据坏了」混淆会让脏数据一直不被发现
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            log.warn("[复核] structured_data 解析失败，按「无结构化数据」处理：{}", e.getOriginalMessage());
            return null;
        }
    }
}
