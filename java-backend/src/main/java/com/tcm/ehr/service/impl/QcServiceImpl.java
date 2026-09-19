package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.LogicChecker;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.dto.QcCheckDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.ScoreResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质控服务实现（批B·2.3）：事前检查 / 逻辑一致性 / 单条评分 / 批量重算。
 *
 * <ul>
 *   <li>判定地基=规则引擎（QcScorer + LogicChecker），LLM 不参与；</li>
 *   <li>批量：分页分批（1000/页）+ Redis SETNX 防重（tcm:task:batch）+ 单条失败跳过汇总；</li>
 *   <li>review_tasks 幂等 upsert（无批量前置清理），查询过滤 is_obsolete=0。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcServiceImpl extends ServiceImpl<RecordMapper, Record> implements com.tcm.ehr.service.IQcService {

    private static final String BATCH_LOCK_KEY = "tcm:task:batch";
    private static final int BATCH_PAGE_SIZE = 1000;
    private static final Pattern NUMERIC = Pattern.compile("^\\d+(\\.\\d+)?(岁|个月|月|天)?$");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final OperationLogger operationLogger;

    @Value("${qc.batch.max-records:30000}")
    private int maxRecords;

    @Override
    public QcCheckVO check(QcCheckDTO dto) {
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        Map<String, Object> data = asMap(dto == null ? null : dto.getStructuredData(),
                raw == null ? null : raw.getStructuredData());

        QcCheckVO vo = new QcCheckVO();
        // 缺失（核心 6 项）
        for (String field : List.of("脉象", "舌象", "证候", "治法", "方剂", "中药")) {
            if (coreMissing(field, data, raw)) {
                vo.getMissingFields().add(field);
            }
        }
        // 格式
        if (raw != null) {
            String age = trim(raw.getAge());
            if (age != null && !NUMERIC.matcher(age).matches()) {
                vo.getFormatErrors().add(new QcCheckVO.FormatError("年龄", "age 非数字：" + age));
            }
            String gender = trim(raw.getGender());
            if (gender != null && !"男".equals(gender) && !"女".equals(gender)) {
                vo.getFormatErrors().add(new QcCheckVO.FormatError("性别", "性别非 男/女：" + gender));
            }
        }
        vo.setDuplicate(false);
        return vo;
    }

    @Override
    public LogicCheckVO checkLogic(LogicCheckDTO dto) {
        List<String> patterns = pick(dto == null ? null : dto.getPatternList());
        List<String> treatments = pick(dto == null ? null : dto.getTreatmentList());
        List<String> formulas = pick(dto == null ? null : dto.getFormulaList());
        List<String> conflicts = LogicChecker.check(patterns, treatments, formulas, List.of(), List.of());
        LogicCheckVO vo = new LogicCheckVO();
        vo.setConflicts(conflicts);
        vo.setConsistent(conflicts.isEmpty());
        return vo;
    }

    @Override
    public ScoreResultVO score(QcScoreDTO dto) {
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        Object sd = dto == null ? null : dto.getStructuredData();
        if (sd == null && raw == null) {
            throw new IllegalArgumentException("structuredData 与 recordId 至少提供一个");
        }
        Map<String, Object> data = asMap(sd, raw == null ? null : raw.getStructuredData());
        return QcScorer.score(data, raw, false);
    }

    @Override
    public QcBatchResultVO scoreBatch(QcBatchDTO dto) {
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(),
                dto == null ? null : dto.getFilters());
        long total = baseMapper.selectCount(wrapper);
        if (total > maxRecords) {
            throw new IllegalArgumentException("超过单次上限（" + maxRecords + " 条），请缩小范围或子集操作");
        }

        boolean locked = acquireLock();
        if (!locked) {
            throw new IllegalArgumentException("任务进行中，请勿重复提交");
        }
        QcBatchResultVO result = new QcBatchResultVO();
        Set<String> seenHash = new HashSet<>();
        try {
            int pageNo = 1;
            while (true) {
                Page<Record> page = baseMapper.selectPage(new Page<>(pageNo, BATCH_PAGE_SIZE), wrapper);
                List<Record> records = page.getRecords();
                if (records.isEmpty()) {
                    break;
                }
                for (Record r : records) {
                    result.setTotal(result.getTotal() + 1);
                    try {
                        processOne(r, result, seenHash);
                    } catch (Exception e) {
                        result.setFailed(result.getFailed() + 1);
                        if (result.getFailureSamples().size() < 50) {
                            result.getFailureSamples().add(
                                    new QcBatchResultVO.Failure(r.getId(), e.getMessage()));
                        }
                        log.warn("[质控重算] 病历 {} 失败: {}", r.getId(), e.getMessage());
                    }
                }
                if (records.size() < BATCH_PAGE_SIZE) {
                    break;
                }
                pageNo++;
            }
            operationLogger.log("批量重算", null, "总数" + result.getTotal() + "，合格" + result.getQualified()
                    + "，待复核" + result.getPendingReview() + "，无效" + result.getInvalid()
                    + "，失败" + result.getFailed());
        } finally {
            releaseLock();
        }
        return result;
    }

    private void processOne(Record r, QcBatchResultVO result, Set<String> seenHash) throws Exception {
        String hash = RecordUtil.textHash(r);
        boolean duplicate = !seenHash.add(hash);

        Map<String, Object> data = asMap(null, r.getStructuredData());
        ScoreResultVO vo = QcScorer.score(data, r, duplicate);

        String status = switch (vo.getGrade()) {
            case "合格" -> "completed";
            case "待复核" -> "reviewing";
            default -> "invalid";
        };
        baseMapper.updateScoreFields(r.getId(), vo.getScore(), vo.getGrade(), status,
                objectMapper.writeValueAsString(vo));
        upsertReviewTask(r, vo);

        switch (vo.getGrade()) {
            case "合格" -> result.setQualified(result.getQualified() + 1);
            case "待复核" -> result.setPendingReview(result.getPendingReview() + 1);
            default -> result.setInvalid(result.getInvalid() + 1);
        }
    }

    /** review_tasks 幂等 upsert（无批量前置清理） */
    private void upsertReviewTask(Record r, ScoreResultVO vo) {
        List<ReviewTask> existing = reviewTaskMapper.selectList(new QueryWrapper<ReviewTask>()
                .eq("record_id", r.getId()).eq("is_obsolete", 0));
        if ("待复核".equals(vo.getGrade())) {
            if (!existing.isEmpty()) {
                ReviewTask t = existing.get(0);
                t.setScore(vo.getScore());
                t.setIssueType(issueType(vo));
                t.setStatus("pending");
                reviewTaskMapper.updateById(t);
            } else {
                ReviewTask t = new ReviewTask();
                t.setRecordId(r.getId());
                t.setStatus("pending");
                t.setIssueType(issueType(vo));
                t.setScore(vo.getScore());
                t.setCreateTime(LocalDateTime.now().withNano(0));
                t.setDeadlineTime(addWorkdays(LocalDateTime.now(), 7));
                t.setIsObsolete(0);
                reviewTaskMapper.insert(t);
            }
        } else {
            for (ReviewTask t : existing) {
                t.setIsObsolete(1);
                reviewTaskMapper.updateById(t);
            }
        }
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

    private LocalDateTime addWorkdays(LocalDateTime start, int days) {
        LocalDateTime d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d.withNano(0);
    }

    // ============ 辅助 ============

    private boolean acquireLock() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(BATCH_LOCK_KEY, "1", Duration.ofSeconds(900)));
        } catch (Exception e) {
            log.warn("[质控重算] Redis 不可用，跳过防重锁: {}", e.getMessage());
            return true;
        }
    }

    private void releaseLock() {
        try {
            redis.delete(BATCH_LOCK_KEY);
        } catch (Exception e) {
            log.warn("[质控重算] 释放锁失败（将由 EX 过期兜底）: {}", e.getMessage());
        }
    }

    private Record loadRaw(String recordId) {
        return recordId == null || recordId.isBlank() ? null : baseMapper.selectById(recordId);
    }

    /** 优先用入参对象，其次解析病历 structured_data 字符串 */
    private Map<String, Object> asMap(Object inline, String json) {
        Object src = inline != null ? inline : json;
        if (src == null) {
            return null;
        }
        try {
            if (src instanceof String s) {
                return s.isBlank() ? null : objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
                });
            }
            return objectMapper.convertValue(src, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> pick(List<Map<String, Object>> list) {
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .map(m -> m.get("content") != null ? m.get("content") : m.get("name"))
                .filter(v -> v != null && !String.valueOf(v).isBlank())
                .map(v -> String.valueOf(v).trim())
                .toList();
    }

    private boolean coreMissing(String field, Map<String, Object> data, Record raw) {
        return switch (field) {
            case "脉象" -> listEmpty(data, "pulseList") && (raw == null || blank(raw.getPulse()));
            case "舌象" -> listEmpty(data, "tongueList") && (raw == null || blank(raw.getTongue()));
            case "证候" -> listEmpty(data, "patternList") && (raw == null || blank(raw.getPattern()));
            case "治法" -> listEmpty(data, "treatmentList");
            case "方剂" -> listEmpty(data, "formulaList");
            case "中药" -> listEmpty(data, "herbs") && (raw == null || blank(raw.getPrescription()));
            default -> false;
        };
    }

    private boolean listEmpty(Map<String, Object> data, String key) {
        return data == null || !(data.get(key) instanceof List<?> list) || list.isEmpty();
    }

    private String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
