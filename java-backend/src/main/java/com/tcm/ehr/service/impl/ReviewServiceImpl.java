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
 * <li>列表：review_tasks 过滤 is_obsolete=0；审核员仅待复核域（status=pending）；</li>
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

    /**
     * 分页查询复核任务列表，只读。
     *
     * <p>只取未失效任务（{@code is_obsolete=0}），按创建时间倒序；审核员强制只看待复核（pending）
     * 域，其余角色按传入状态过滤（兼容中文与英文状态名）。关联病历缺失的任务直接跳过，不占位。</p>
     *
     * @param page 页码，非法时取第 1 页
     * @param pageSize 每页条数，非法时取 20
     * @param status 状态筛选（可空，非审核员生效）
     * @return 命中总数与任务列表项
     */
    @Override
    public ReviewTasksVO listTasks(Integer page, Integer pageSize, String status) {
        // 1. 分页参数非法时回退为第 1 页 / 每页 20 条
        int p = page != null && page > 0 ? page : 1;
        int s = pageSize != null && pageSize > 0 ? pageSize : 20;

        // 2. 组装查询条件：只取未失效任务，按创建时间倒序
        QueryWrapper<ReviewTask> w = new QueryWrapper<>();
        w.eq("is_obsolete", 0);
        // 3. 数据域：审核员强制只看待复核，其余角色按传入状态过滤
        if (RecordFilter.ROLE_AUDITOR.equals(RequestUtils.currentRole())) {
            w.eq("status", "pending");
        } else {
            String dbStatus = dbStatus(status);
            if (dbStatus != null) {
                w.eq("status", dbStatus);
            }
        }
        w.orderByDesc("create_time");

        // 4. 分页查询
        Page<ReviewTask> pg = baseMapper.selectPage(new Page<>(p, s), w);
        // 5. 组装返回：总数与任务列表项（关联病历缺失的跳过，不占位）
        ReviewTasksVO vo = new ReviewTasksVO();
        vo.setTotal(pg.getTotal());
        for (ReviewTask t : pg.getRecords()) {
            Record r = recordMapper.selectById(t.getRecordId());
            if (r == null) {
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
            rv.setStructuredData(parse(r.getStructuredData()));
            vo.getTasks().add(rv);
        }
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
        if (RecordFilter.ROLE_AUDITOR.equals(RequestUtils.currentRole())
                && !"待复核".equals(r.getGrade())) {
            throw new ForbiddenException("无权复核非待复核病历");
        }

        List<ReviewTask> tasks = baseMapper.selectList(new QueryWrapper<ReviewTask>()
                .eq("record_id", recordId).eq("is_obsolete", 0).eq("status", "pending"));
        if (tasks.isEmpty()) {
            // 统一抛 ResourceNotFoundException：返回 null 会让 Controller 再造一个错误码，
            // 同一条"资源不存在"就有 1006/2003 两个说法，且 data 为 null
            throw new ResourceNotFoundException(1006, "复核记录不存在或状态已完结");
        }
        ReviewTask task = tasks.get(0);

        // ① 人工修正（可选）：合并 correctedData 回写 structured_data
        if (dto != null && dto.getCorrectedData() != null) {
            String json;
            try {
                json = objectMapper.writeValueAsString(dto.getCorrectedData());
            } catch (JacksonException e) {
                throw new IllegalArgumentException("修正数据格式错误");
            }
            recordMapper.updateStructuredData(recordId, json);
            r.setStructuredData(json);
        }

        // ② 自动重算（判定地基仍是规则）
        ScoreResultVO sr = QcScorer.score(asMap(r.getStructuredData()), r, false, qcRuleStore.get());
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

        // ③ 任务流转：仍待复核 → 保持 pending 并刷新；否则完成
        ReviewResultVO result = new ReviewResultVO();
        result.setScore(sr.getScore());
        if ("待复核".equals(sr.getGrade())) {
            task.setStatus("pending");
            task.setScore(sr.getScore());
            task.setIssueType(issueType(sr));
            baseMapper.updateById(task);
            result.setStatus("待复核");
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
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status) {
            case "待复核", "pending" -> "pending";
            case "已完成", "completed" -> "completed";
            default -> status;
        };
    }

    private String issueType(ScoreResultVO vo) {
        if (!vo.getLogicConflicts().isEmpty()) {
            return "逻辑冲突";
        }
        if (vo.getDeductions().stream().anyMatch(d -> "核心字段缺失".equals(d.getType()))) {
            return "缺失字段";
        }
        return "评分不达标";
    }

    /** 解析 structured_data 字符串为对象（供左侧对照）；失败/空返回 null */
    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 解析结构化数据；空或坏 JSON 返回空 map（复核时按"未结构化"继续，不中断） */
    private Map<String, Object> asMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return null;
        }
    }
}
