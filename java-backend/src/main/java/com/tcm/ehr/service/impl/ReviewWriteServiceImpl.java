package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.service.IReviewWriteService;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.domain.dto.ReviewDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.ReviewResultVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 复核提交的写库段（批次 26.4 / 26.e 定案 A）。
 *
 * <p>原来「ES 归一 + 写库」同处一个 {@code @Transactional} 方法，而归一要走 ES 网络往返，
 * 期间数据库连接一直被占着，ES 一慢连接池就被拖住。现在归一上移到事务外，
 * 见 {@link ReviewServiceImpl#review}；本类只承载事务内的读改写。</p>
 *
 * <p>语义不变：归一失败发生在进入本类之前，异常照旧上抛（ES 不可用 → 503）且零写入；
 * 权限、隔离、防丢更新的读时指纹校验都仍在事务内原位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewWriteServiceImpl implements IReviewWriteService {

    private final RecordMapper recordMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ObjectMapper objectMapper;
    private final QcRuleStore qcRuleStore;

    /**
     * 事务内完成「校验 → 回写结构化数据 → 自动重算 → 任务流转」。
     *
     * <p>三段写库必须同生共死，否则中途失败会出现「分数已回写、任务仍 pending」的对不上。</p>
     *
     * @param recordId 病历 ID
     * @param dto      已由调用方在事务外归一的复核数据（{@code correctedData} 会被就地改写）
     * @return status=复核后状态；score=重算得分
     * @throws ResourceNotFoundException 病历或复核任务不存在
     * @throws ForbiddenException 无权复核该病历，或病历已被隔离
     * @throws ConcurrentOperationException 读时指纹与服务端当前不一致（批次 25.15）
     */
    @Transactional(rollbackFor = Exception.class)
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

        List<ReviewTask> tasks = reviewTaskMapper.selectList(new QueryWrapper<ReviewTask>()
                .eq("record_id", recordId).eq("is_obsolete", 0).eq("status", "pending"));
        if (tasks.isEmpty()) {
            // 统一抛 ResourceNotFoundException：返回 null 会让 Controller 再造一个错误码，
            // 同一条"资源不存在"就有 1006/2003 两个说法，且 data 为 null
            throw new ResourceNotFoundException(1006, "复核记录不存在或状态已完结");
        }
        ReviewTask task = tasks.get(0);

        // 0. 乐观并发校验（批次 25.15）：提交带回的读时指纹与服务端当前不一致，
        //    说明在你读取之后有人改过这条病历（复核修正 / 清洗 / 批量解析都会写 structured_data），
        //    此时继续会让本次人工修正静默覆盖对方的修改 —— 即登记在案的 lost update。
        //    指纹为空表示调用方不参与校验（兼容旧客户端）；非空必须严格相等。
        if (dto != null && dto.getFingerprint() != null && !dto.getFingerprint().isBlank()) {
            String current = ReviewTaskUtil.fingerprint(r.getStructuredData(), r.getScore(), r.getGrade());
            if (!current.equals(dto.getFingerprint())) {
                throw new ConcurrentOperationException("该病历已被其他人修改或复核，请刷新后重试");
            }
        }

        // 1. 人工修正（可选）：correctedData 已在事务外由 EntityNormalizer 归一
        //    （见 ReviewServiceImpl#review），这里只负责序列化与回写。
        if (dto != null && dto.getCorrectedData() != null) {
            Map<String, Object> corrected = dto.getCorrectedData();

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
            reviewTaskMapper.updateById(task);
            result.setStatus("待复核");
        } else if ("无效".equals(sr.getGrade())) {
            // 批量路径判无效时写 obsolete/is_obsolete=1，复核路径原来写 completed ——
            // 同一结论两条路径两种终态，按任务表统计复核工作量时口径不可比
            task.setScore(sr.getScore());
            task.setStatus("obsolete");
            task.setIsObsolete(1);
            task.setReviewedBy(RequestUtils.currentUsername());
            task.setCompletedTime(LocalDateTime.now().withNano(0));
            reviewTaskMapper.updateById(task);
            result.setStatus("无效");
        } else {
            task.setStatus("completed");
            task.setScore(sr.getScore());
            task.setReviewedBy(RequestUtils.currentUsername());
            task.setCompletedTime(LocalDateTime.now().withNano(0));
            reviewTaskMapper.updateById(task);
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
