package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
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
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.po.ReviewTask;
import com.tcm.ehr.domain.vo.DeductionStatsVO;
import com.tcm.ehr.domain.vo.LogicCheckVO;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcCheckVO;
import com.tcm.ehr.domain.vo.QcRulesVO;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质控服务实现（批B·2.3；批Q 规则可配置）。
 *
 * <ul>
 *   <li>判定地基 = 规则引擎（QcScorer + LogicChecker），规则来自 {@link QcRuleStore}；</li>
 *   <li>批量：分页分批（1000/页） + Redis SETNX 防重（tcm:task:batch）；</li>
 *   <li>review_tasks 幂等 upsert（查询过滤 is_obsolete=0）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcServiceImpl extends ServiceImpl<RecordMapper, Record> implements com.tcm.ehr.service.IQcService {

    private static final String BATCH_LOCK_KEY = "tcm:task:batch";
    private static final int BATCH_PAGE_SIZE = 1000;
    /** 扣分聚合/扫描上限 */
    private static final int MAX_SCAN_RECORDS = 3000;

    private final ReviewTaskMapper reviewTaskMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final OperationLogger operationLogger;
    private final QcRuleStore ruleStore;

    @Value("${qc.batch.max-records:30000}")
    private int maxRecords;

    // ------------------------------------------------------------------ 检查

    @Override
    public QcCheckVO check(QcCheckDTO dto) {
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        Map<String, Object> data = asMap(dto == null ? null : dto.getStructuredData(),
                raw == null ? null : raw.getStructuredData());
        QcRuleSet rules = ruleStore.get();

        QcCheckVO vo = new QcCheckVO();
        for (QcRuleSet.Element el : rules.getCompleteness().getElements()) {
            if (!structuredPresent(el.getSource(), data) && !rawPresent(el.getFallback(), raw)) {
                vo.getMissingFields().add(el.getName());
            }
        }
        if (raw != null) {
            for (QcRuleSet.FormatRule fr : rules.getFormat()) {
                String v = rawValue(raw, fr.getField());
                if (v != null && !formatOk(fr, v)) {
                    String label = fr.getLabel() == null ? fr.getField() : fr.getLabel();
                    vo.getFormatErrors().add(new QcCheckVO.FormatError(label,
                            (fr.getReason() == null ? label + "格式不正确" : fr.getReason()) + "：" + v));
                }
            }
        }
        vo.setDuplicate(false);
        return vo;
    }

    @Override
    public LogicCheckVO checkLogic(LogicCheckDTO dto) {
        List<String> patterns = pick(dto == null ? null : dto.getPatternList());
        List<String> conflicts = LogicChecker.check(patterns, List.of(), List.of(), List.of(),
                ruleStore.get().getConsistency());
        LogicCheckVO vo = new LogicCheckVO();
        vo.setConflicts(conflicts);
        vo.setConsistent(conflicts.isEmpty());
        return vo;
    }

    // ------------------------------------------------------------------ 评分

    @Override
    public ScoreResultVO score(QcScoreDTO dto) {
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        Object sd = dto == null ? null : dto.getStructuredData();
        if (sd == null && raw == null) {
            throw new IllegalArgumentException("structuredData 与 recordId 至少提供一个");
        }
        Map<String, Object> data = asMap(sd, raw == null ? null : raw.getStructuredData());
        return QcScorer.score(data, raw, false, ruleStore.get());
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
            QcRuleSet rules = ruleStore.get();
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
                        processOne(r, result, seenHash, rules);
                    } catch (Exception e) {
                        result.setFailed(result.getFailed() + 1);
                        if (result.getFailureSamples().size() < 50) {
                            result.getFailureSamples().add(new QcBatchResultVO.Failure(r.getId(), e.getMessage()));
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

    private void processOne(Record r, QcBatchResultVO result, Set<String> seenHash, QcRuleSet rules) throws Exception {
        String hash = RecordUtil.textHash(r);
        boolean duplicate = !seenHash.add(hash);

        Map<String, Object> data = asMap(null, r.getStructuredData());
        ScoreResultVO vo = QcScorer.score(data, r, duplicate, rules);

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

    /** review_tasks 幂等 upsert */
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

    // ------------------------------------------------------------------ 规则

    @Override
    public QcRulesVO rules() {
        return buildRulesVO();
    }

    @Override
    public QcRulesVO updateRules(QcRuleSet rules) {
        ruleStore.update(rules);
        return buildRulesVO();
    }

    @Override
    public QcRulesVO resetRules() {
        ruleStore.reset();
        return buildRulesVO();
    }

    /** 组装：规则 + 自然语言描述 + 目录 + 告警（单一来源） */
    private QcRulesVO buildRulesVO() {
        QcRulesVO vo = new QcRulesVO(ruleStore.get(), ruleStore.warnings());
        vo.setDescriptions(com.tcm.ehr.common.config.QcRuleDescriber.describe(ruleStore.get()));
        vo.setCatalogElements(com.tcm.ehr.common.config.QcRuleDescriber.catalogElements());
        vo.setCatalogFormats(com.tcm.ehr.common.config.QcRuleDescriber.catalogFormats());
        return vo;
    }

    // ------------------------------------------------------------------ 扣分聚合

    @Override
    public DeductionStatsVO deductionStats(FiltersDTO filters) {
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(), filters);
        DeductionStatsVO vo = new DeductionStatsVO();
        Map<String, int[]> byType = new LinkedHashMap<>();
        Map<String, int[]> byItem = new LinkedHashMap<>();
        Map<String, Integer> gradeDist = new LinkedHashMap<>();
        int scanned = 0;
        int totalPoints = 0;
        int pageNo = 1;
        while (true) {
            Page<Record> page = baseMapper.selectPage(new Page<>(pageNo, BATCH_PAGE_SIZE), wrapper);
            List<Record> records = page.getRecords();
            if (records.isEmpty()) {
                break;
            }
            for (Record r : records) {
                if (scanned >= MAX_SCAN_RECORDS) {
                    vo.setTruncated(true);
                    break;
                }
                scanned++;
                gradeDist.merge(r.getGrade() == null ? "未评分" : r.getGrade(), 1, Integer::sum);
                ScoreResultVO sr = scoreOf(r);
                if (sr == null || sr.getDeductions() == null) {
                    continue;
                }
                for (ScoreResultVO.Deduction d : sr.getDeductions()) {
                    totalPoints += d.getPoints();
                    int[] a = byType.computeIfAbsent(d.getType(), k -> new int[2]);
                    a[0]++;
                    a[1] += d.getPoints();
                    int[] b = byItem.computeIfAbsent(d.getType() + "|" + d.getItem(), k -> new int[2]);
                    b[0]++;
                    b[1] += d.getPoints();
                }
            }
            if (vo.isTruncated() || records.size() < BATCH_PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            vo.getByType().add(new DeductionStatsVO.ByType(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        List<DeductionStatsVO.ByItem> items = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byItem.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            items.add(new DeductionStatsVO.ByItem(k[0], k.length > 1 ? k[1] : "", e.getValue()[0], e.getValue()[1]));
        }
        items.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        vo.setByItem(new ArrayList<>(items.subList(0, Math.min(20, items.size()))));
        vo.setScanned(scanned);
        vo.setTotalPoints(totalPoints);
        vo.setGradeDist(gradeDist);
        return vo;
    }

    /** 取该病历的评分结果：优先读 qc_results，缺失则按当前规则现算 */
    private ScoreResultVO scoreOf(Record r) {
        if (r.getQcResults() != null && !r.getQcResults().isBlank()) {
            try {
                return objectMapper.readValue(r.getQcResults(), ScoreResultVO.class);
            } catch (Exception ignored) {
                // 落库格式异常则退回现算
            }
        }
        try {
            return QcScorer.score(asMap(null, r.getStructuredData()), r, false, ruleStore.get());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 辅助

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

    private boolean structuredPresent(String key, Map<String, Object> data) {
        return key != null && data != null && data.get(key) instanceof List<?> list && !list.isEmpty();
    }

    private boolean rawPresent(List<String> fields, Record raw) {
        if (raw == null || fields == null) {
            return false;
        }
        for (String f : fields) {
            if (rawValue(raw, f) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean formatOk(QcRuleSet.FormatRule fr, String v) {
        if ("enum".equalsIgnoreCase(fr.getType())) {
            return fr.getValues() != null && fr.getValues().contains(v);
        }
        if (fr.getExpr() == null || fr.getExpr().isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(fr.getExpr()).matcher(v).matches();
        } catch (Exception e) {
            return true;
        }
    }

    private String rawValue(Record r, String field) {
        if (r == null || field == null) {
            return null;
        }
        String v = switch (field) {
            case "registrationNo" -> r.getRegistrationNo();
            case "outpatientNo" -> r.getOutpatientNo();
            case "gender" -> r.getGender();
            case "age" -> r.getAge();
            case "westernDiagnosis" -> r.getWesternDiagnosis();
            case "tcmDiagnosis" -> r.getTcmDiagnosis();
            case "presentIllness" -> r.getPresentIllness();
            case "chiefComplaint" -> r.getChiefComplaint();
            case "selfReport" -> r.getSelfReport();
            case "inspection" -> r.getInspection();
            case "pulse" -> r.getPulse();
            case "tongue" -> r.getTongue();
            case "physicalExam" -> r.getPhysicalExam();
            case "pattern" -> r.getPattern();
            case "prescription" -> r.getPrescription();
            case "followUp" -> r.getFollowUp();
            case "treatmentEffect" -> r.getTreatmentEffect();
            default -> null;
        };
        return v == null || v.isBlank() ? null : v.trim();
    }
}
