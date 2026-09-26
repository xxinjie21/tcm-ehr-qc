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
import java.util.UUID;
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
    /** 防重锁 TTL：超过它就认为上一轮已经异常结束，允许重新提交 */
    private static final long LOCK_TTL_SECONDS = 900;
    /** 降级为进程内锁时用的哨兵令牌（与 Redis 的 UUID 令牌区分开） */
    private static final String LOCAL_LOCK_TOKEN = "local";
    /** Redis 不可用时的兜底锁；final 且有初值 → 不进 @RequiredArgsConstructor */
    private final java.util.concurrent.locks.ReentrantLock localLock = new java.util.concurrent.locks.ReentrantLock();

    /** 只删除「值等于本次令牌」的锁，GET 与 DEL 之间不可被打断 */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> RELEASE_IF_OWNER =
            org.springframework.data.redis.core.script.RedisScript.of(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

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

    /**
     * 单条完整性 + 格式检查，只读、不落库。
     *
     * <p>结构化数据取请求优先、病历回退（缺省时按 {@code recordId} 载入）；完整性元素在
     * 「结构化字段缺失且原始字段回退也为空」时记入缺失清单，格式规则仅对已载入的原始病历逐条
     * 校验。单条检查不做重复判定，{@code duplicate} 恒为 false。</p>
     *
     * @param dto 检查请求（recordId + 可选 structuredData），可为 null
     * @return 缺失字段清单与格式错误清单
     */
    @Override
    public QcCheckVO check(QcCheckDTO dto) {
        // 1. 载入原始病历（缺省时按 recordId 取，无则 null）
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        // 2. 组装结构化数据：请求内联优先、病历回退
        Map<String, Object> data = asMap(dto == null ? null : dto.getStructuredData(),
                raw == null ? null : raw.getStructuredData());
        // 3. 取当前生效规则
        QcRuleSet rules = ruleStore.get();

        // 4. 完整性检查：结构化字段缺失且原始字段回退也为空，才记入缺失清单
        QcCheckVO vo = new QcCheckVO();
        for (QcRuleSet.Element el : rules.getCompleteness().getElements()) {
            if (!structuredPresent(el.getSource(), data) && !rawPresent(el.getFallback(), raw)) {
                vo.getMissingFields().add(el.getName());
            }
        }
        // 5. 格式检查：仅对已载入的原始病历逐条校验
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
        // 6. 单条检查不做重复判定，duplicate 恒为 false
        vo.setDuplicate(false);
        return vo;
    }

    /**
     * 一致性（逻辑冲突）检查，只读。
     *
     * <p>把请求中的证型 / 治法 / 方剂列表交给 {@link LogicChecker}，按当前一致性规则求冲突项；
     * {@code dto} 为 null 时按空数据检查，结果视为无冲突。</p>
     *
     * @param dto 含 patternList / treatmentList / formulaList 的检查请求，可为 null
     * @return 冲突清单及「是否一致」标志（冲突为空即一致）
     */
    @Override
    public LogicCheckVO checkLogic(LogicCheckDTO dto) {
        // 1. 组装检查数据（dto 为空按空数据检查）
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (dto != null) {
            data.put("patternList", dto.getPatternList());
            data.put("treatmentList", dto.getTreatmentList());
            data.put("formulaList", dto.getFormulaList());
        }
        // 2. 按当前一致性规则求冲突项
        List<String> conflicts = LogicChecker.check(data, ruleStore.get().getConsistency());
        // 3. 冲突为空即视为一致
        LogicCheckVO vo = new LogicCheckVO();
        vo.setConflicts(conflicts);
        vo.setConsistent(conflicts.isEmpty());
        return vo;
    }

    // ------------------------------------------------------------------ 评分

    /**
     * 单条评分，只读（不写回 records，也不生成复核任务）。
     *
     * <p>结构化数据取请求优先、病历回退；{@code structuredData} 与 {@code recordId} 至少提供
     * 其一。评分按当前规则执行，重复标志固定为 false。</p>
     *
     * @param dto 评分请求（recordId + 可选 structuredData），可为 null
     * @return 得分、等级与扣分明细
     * @throws IllegalArgumentException 既无 structuredData 也无 recordId 时抛出
     */
    @Override
    public ScoreResultVO score(QcScoreDTO dto) {
        // 1. 载入原始病历
        Record raw = loadRaw(dto == null ? null : dto.getRecordId());
        // 2. 取请求内联的结构化数据
        Object sd = dto == null ? null : dto.getStructuredData();
        // 3. 两者都缺 → 参数错误，不落到空评分
        if (sd == null && raw == null) {
            throw new IllegalArgumentException("structuredData 与 recordId 至少提供一个");
        }
        // 4. 组装数据并评分（只读：不写回 records，也不生成复核任务）
        Map<String, Object> data = asMap(sd, raw == null ? null : raw.getStructuredData());
        return QcScorer.score(data, raw, false, ruleStore.get());
    }

    /**
     * 按筛选范围批量重算质控（同步执行，有副作用）。
     *
     * <p>范围先经数据域过滤；总数超过 {@code qc.batch.max-records}（默认 30000）直接拒绝，
     * 避免同步接口超时与防重锁被长期占用。执行前取 Redis 防重锁（Redis 不可用时降级为进程内
     * 锁），已被占用则拒绝重复提交；随后按 1000 条/页循环，逐条现算评分、回写 records 评分字段
     * 并幂等 upsert review_tasks，批内以 21 字段文本哈希去重。单条失败只累计计数、不中断整批，
     * 失败明细最多保留 50 条。结束时无论成败都会释放锁。</p>
     *
     * @param dto 批量请求，filters 为筛选条件，可为 null（表示不限）
     * @return 总数、合格 / 待复核 / 无效 / 失败计数及失败样例
     * @throws IllegalArgumentException 超出单次上限或已有任务在跑时抛出
     */
    @Override
    public QcBatchResultVO scoreBatch(QcBatchDTO dto) {
        // 1. 构造数据域过滤条件（角色可见范围 + 用户筛选）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(),
                dto == null ? null : dto.getFilters());
        // 2. 先统计总数，用于上限校验
        long total = baseMapper.selectCount(wrapper);
        // 3. 超过单次上限直接拒绝：本接口同步执行，避免超时与防重锁被长期占用
        if (total > maxRecords) {
            // 上限维持 30000 不提高：本接口是同步的（前端超时 200s、后端还握着 900s 的防重锁），
            // 真跑 3.5 万条只会把「干净的 400」换成「前端超时 + 锁被占满」。所以把文案写成
            // 可执行的下一步，而不是让用户反复试。
            throw new IllegalArgumentException("本次范围 " + total + " 条，超过单次上限 " + maxRecords
                    + " 条。请按科室或就诊时间分批重算。");
        }

        // 4. 取 Redis 防重锁（不可用时降级为进程内锁）
        String lockToken = acquireLock();
        // 5. 锁已被占用 → 拒绝重复提交
        if (lockToken == null) {
            throw new IllegalArgumentException("任务进行中，请勿重复提交");
        }
        // 6. 初始化统计结果与批内去重集合（21 字段文本哈希）
        QcBatchResultVO result = new QcBatchResultVO();
        Set<String> seenHash = new HashSet<>();
        try {
            // 7. 取规则快照，整批共用（避免逐条重复读取）
            QcRuleSet rules = ruleStore.get();
            // 8. 按 1000 条/页分页扫描，直到取空或不足一页
            int pageNo = 1;
            while (true) {
                Page<Record> page = baseMapper.selectPage(new Page<>(pageNo, BATCH_PAGE_SIZE), wrapper);
                List<Record> records = page.getRecords();
                if (records.isEmpty()) {
                    break;
                }
                for (Record r : records) {
                    result.setTotal(result.getTotal() + 1);
                    // 9. 单条失败只累计计数、不中断整批（明细最多保留 50 条）
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
            // 10. 记录操作日志（含总数与各状态计数）
            operationLogger.log("批量重算", RecordFilter.describe(dto == null ? null : dto.getFilters()), "总数" + result.getTotal() + "，合格" + result.getQualified()
                    + "，待复核" + result.getPendingReview() + "，无效" + result.getInvalid()
                    + "，失败" + result.getFailed());
        // 11. 无论成败都释放锁
        } finally {
            releaseLock(lockToken);
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

    /**
     * 读取当前生效的质控规则，只读。
     *
     * <p>返回规则本体、自然语言描述、字段目录、加载告警与一致性摘要（同一来源组装）。</p>
     *
     * @return 当前规则视图
     */
    @Override
    public QcRulesVO rules() {
        return buildRulesVO();
    }

    /**
     * 覆盖当前质控规则，并返回覆盖后的视图。
     *
     * <p>规则写入 {@link QcRuleStore} 后立即生效，后续检查 / 评分 / 重算都使用新规则。</p>
     *
     * @param rules 新的规则集
     * @return 覆盖后的规则视图（含加载告警）
     */
    @Override
    public QcRulesVO updateRules(QcRuleSet rules) {
        ruleStore.update(rules);
        return buildRulesVO();
    }

    /**
     * 恢复内置默认规则，并返回恢复后的视图。
     *
     * @return 重置后的规则视图
     */
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
        // 一致性那行的摘要由规则数据拼装下发 —— 前端写死过一次「（证候 → 中药 / 舌象 / 脉象）」，
        // 而规则里没有舌象/脉象，改规则也不改文案
        vo.setConsistencySummary(com.tcm.ehr.common.config.QcRuleDescriber.consistencySummary(ruleStore.get()));
        return vo;
    }

    // ------------------------------------------------------------------ 扣分聚合

    /**
     * 扣分聚合统计，只读。
     *
     * <p>范围先经数据域过滤，按 1000 条/页扫描，最多扫描 {@value #MAX_SCAN_RECORDS} 条；
     * 触顶时置 {@code truncated} 标记并停止。每条优先读库内已存评分结果，缺失则按当前规则现算，
     * 再按扣分类型与「类型|条目」两级聚合次数和分值，同时统计等级分布。明细按分值降序取前 20。</p>
     *
     * @param filters 用户筛选条件，可为 null（表示不限）
     * @return 类型 / 条目扣分聚合、等级分布、扫描条数与是否被截断
     */
    @Override
    public DeductionStatsVO deductionStats(FiltersDTO filters) {
        // 1. 构造数据域过滤条件（角色可见范围 + 用户筛选）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(), filters);
        // 2. 初始化聚合容器：按类型、按条目、等级分布
        DeductionStatsVO vo = new DeductionStatsVO();
        Map<String, int[]> byType = new LinkedHashMap<>();
        Map<String, int[]> byItem = new LinkedHashMap<>();
        Map<String, Integer> gradeDist = new LinkedHashMap<>();
        int scanned = 0;
        int totalPoints = 0;
        int pageNo = 1;
        // 3. 分页扫描，每页 1000 条，最多扫 MAX_SCAN_RECORDS 条
        while (true) {
            Page<Record> page = baseMapper.selectPage(new Page<>(pageNo, BATCH_PAGE_SIZE), wrapper);
            List<Record> records = page.getRecords();
            if (records.isEmpty()) {
                break;
            }
            for (Record r : records) {
                // 4. 触顶即置截断标记，停止本页剩余累计
                if (scanned >= MAX_SCAN_RECORDS) {
                    vo.setTruncated(true);
                    break;
                }
                // 5. 计入扫描数并累计等级分布（未评分的归入「未评分」）
                scanned++;
                gradeDist.merge(r.getGrade() == null ? "未评分" : r.getGrade(), 1, Integer::sum);
                // 6. 取评分结果：优先读库内 qc_results，缺失则按当前规则现算
                ScoreResultVO sr = scoreOf(r);
                if (sr == null || sr.getDeductions() == null) {
                    continue;
                }
                // 7. 按「类型」与「类型|条目」两级累计次数与分值
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
        // 8. 组装类型聚合结果
        for (Map.Entry<String, int[]> e : byType.entrySet()) {
            vo.getByType().add(new DeductionStatsVO.ByType(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        // 9. 组装条目明细，按分值降序取前 20
        List<DeductionStatsVO.ByItem> items = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byItem.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            items.add(new DeductionStatsVO.ByItem(k[0], k.length > 1 ? k[1] : "", e.getValue()[0], e.getValue()[1]));
        }
        items.sort((a, b) -> Integer.compare(b.getPoints(), a.getPoints()));
        vo.setByItem(new ArrayList<>(items.subList(0, Math.min(20, items.size()))));
        // 10. 回填扫描条数、总分与等级分布
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

    /**
     * 取防重锁：成功返回本次的锁令牌，已被别人持有返回 {@code null}。
     *
     * <p>Redis 不可用时<b>降级为进程内锁</b>，而不是原来的「放行」—— 放行会让并发提交
     * 真的跑两份，产生重复扣分与重复 review_tasks。本项目单实例部署，进程内锁在这个前提下
     * 与 Redis 锁等效；<b>多实例部署下进程内锁无效</b>，那时 Redis 挂了就只能拒绝提交
     * （日志里已写明这条限制）。</p>
     */
    private String acquireLock() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean ok = redis.opsForValue()
                    .setIfAbsent(BATCH_LOCK_KEY, token, Duration.ofSeconds(LOCK_TTL_SECONDS));
            return Boolean.TRUE.equals(ok) ? token : null;
        } catch (Exception e) {
            log.warn("[质控重算] Redis 不可用，降级为进程内防重锁（多实例部署下不生效）: {}", e.getMessage());
            return localLock.tryLock() ? LOCAL_LOCK_TOKEN : null;
        }
    }

    /**
     * 释放锁：只删「本次持有」的那一把。
     *
     * <p>原来直接 {@code redis.delete(BATCH_LOCK_KEY)} —— 900 秒 TTL 过期后锁可能已被别人取走，
     * 无条件删就是把别人的锁删了，防重彻底失效。用 Lua 原子比对 value 再删，避免
     * 「比对通过、删除前恰好过期被他人取走」的竞态。</p>
     */
    private void releaseLock(String token) {
        if (token == null) {
            return;
        }
        if (LOCAL_LOCK_TOKEN.equals(token)) {
            localLock.unlock();
            return;
        }
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(BATCH_LOCK_KEY), token);
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
