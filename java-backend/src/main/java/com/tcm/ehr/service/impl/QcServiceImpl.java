package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.utils.LogicChecker;
import com.tcm.ehr.common.utils.QcScorer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.LogicCheckDTO;
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
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 质控服务实现。
 *
 * <ul>
 * <li>判定地基 = 规则引擎（QcScorer + LogicChecker），规则来自 {@link QcRuleStore}；</li>
 * <li>批量重算（§七 L5 起异步）：不在本类的请求线程里跑，改由 {@code QcBatchServiceImpl}
 * 以 {@code qc_task} 表 + 固定并发 1 的 worker 执行；本类只保留单条处理
 * {@link #processOne}，分页取数、进度落库、取消与防重都在那边。</li>
 * <li>review_tasks 幂等 upsert（查询过滤 is_obsolete=0）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcServiceImpl extends ServiceImpl<RecordMapper, Record> implements com.tcm.ehr.service.IQcService {

    private static final int BATCH_PAGE_SIZE = 1000;
    /**
     * 扣分聚合的扫描上限：40000（目标数据集规模，实测 35355 条 × 1.13 KB/条 ≈ 40MB）。
     * 触顶时置 {@code truncated} 标记 —— 这是「真的超过上限」，不再是无谓的 3000 硬顶。
     */
    private static final int MAX_SCAN_RECORDS = 40000;
    /** 回退子集的分批回查大小：只对「库内没有 qc_results」的记录做，故通常很小 */
    private static final int FALLBACK_CHUNK = 500;

    private final ReviewTaskMapper reviewTaskMapper;
    private final ObjectMapper objectMapper;
    private final QcRuleStore ruleStore;

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
     * <p>把请求中的证型 / 治法 / 中药 / 方剂列表交给 {@link LogicChecker}，按当前一致性规则求冲突项；
     * {@code dto} 为 null 时按空数据检查，结果视为无冲突。</p>
     * <p>⚠️ 中药的 map key 必须是 {@code herbs}（= {@code EntityTypes} 里 herb 的 structuredKey），
     * 写成 {@code herbList}（DTO 的属性名）会让「证候 → 中药」规则永远取不到值。</p>
     *
     * @param dto 含 patternList / treatmentList / formulaList / herbList 的检查请求，可为 null
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
            data.put("herbs", dto.getHerbList());
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
     * 处理单条：现算评分 → 回写 records → 幂等 upsert 复核任务 → 计入分级统计。
     *
     * <p>§七 L5：供异步 worker（{@code QcBatchServiceImpl}）复用本方法处理单条，
     * 避免在两个类里实现两份「现算 + 回写 + 复核任务」逻辑（两者口径一旦分岔就会让重算结果对不上）。</p>
     *
     * <p><b>为什么是 public</b>：Spring 基于代理的 {@code @Transactional} <b>只对 public 方法生效</b>。
     * 本方法原为包级可见，注解加在上面等于没加（编译通过、运行期静默不开事务）；
     * 调用方是<b>另一个 Bean</b>（{@code QcBatchServiceImpl}），走的是代理，改 public 后才真正生效。</p>
     *
     * <p><b>事务边界</b>：本方法先写 {@code records} 再 upsert {@code review_tasks}，
     * 中途异常会留下「分数已写、复核任务未建」的脏状态，故整段包在一个事务里。
     * {@code rollbackFor = Exception.class} 必写：默认只回滚 RuntimeException，
     * 而本方法 {@code throws Exception}，漏了会静默不回滚。
     * 逐条一个事务是<b>刻意的</b>：调用方按条 catch，一条失败不该把整批已成功的回滚掉。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void processOne(Record r, QcBatchResultVO result, Set<String> seenHash, QcRuleSet rules) throws Exception {
        // 1. 批内按 21 字段文本哈希判重（与导入去重、清洗去重同口径）
        String hash = RecordUtil.textHash(r);
        boolean duplicate = !seenHash.add(hash);

        // 2. 现算评分：读当前规则集，规则改完重算即生效
        Map<String, Object> data = asMap(null, r.getStructuredData());
        ScoreResultVO vo = QcScorer.score(data, r, duplicate, rules);

        // 3. 回写评分字段与 qc_results（留档可溯源）
        String status = switch (vo.getGrade()) {
            case "合格" -> "completed";
            case "待复核" -> "reviewing";
            default -> "invalid";
        };
        baseMapper.updateScoreFields(r.getId(), vo.getScore(), vo.getGrade(), status,
                objectMapper.writeValueAsString(vo));

        // 4. 复核任务幂等维护
        upsertReviewTask(r, vo);

        // 5. 计入分级统计
        switch (vo.getGrade()) {
            case "合格" -> result.setQualified(result.getQualified() + 1);
            case "待复核" -> result.setPendingReview(result.getPendingReview() + 1);
            default -> result.setInvalid(result.getInvalid() + 1);
        }
    }

    /**
     * 复核任务幂等 upsert：待复核则新增/更新，否则把该病历未作废的任务置为作废。
     *
     * <p>作废而不是删除：历史复核轨迹要留，查询侧按 {@code is_obsolete=0} 过滤。</p>
     *
     * <p><b>为什么走 SQL 语义 upsert 而不再「先查后写」</b>（批次 4）：DB 侧已加
     * {@code uk_record_active}（生成列，约束「一个病历至多一条活跃任务」）。
     * 原来的 {@code selectList → update / insert} 是 check-then-act，并发两个线程
     * 都能查到空、都去 insert，其中一个撞唯一键 → 从「静默重复」变成「整批 500」。
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 由唯一索引直接仲裁，没有这个窗口。</p>
     *
     * <p>作废路径不需要唯一键仲裁（更新不产生新行），仍用一条 UPDATE。</p>
     */
    private void upsertReviewTask(Record r, ScoreResultVO vo) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if ("待复核".equals(vo.getGrade())) {
            // 1. 原子 upsert：已有活跃行就刷新，没有就新建（时限默认 7 个工作日）
            //    复核任务打组织标记——写入时打标，避免查询期 JOIN（QueryWrapper 不便于 JOIN）
            reviewTaskMapper.upsertPending(r.getId(), r.getGroupId(), vo.getScore(),
                    issueType(vo), now);
        } else {
            // 2. 已达标则把未作废任务作废而不是删除，历史复核轨迹要留
            reviewTaskMapper.obsoleteActive(r.getId(), now);
        }
    }

    /** 问题类型取最"重"的一项：逻辑冲突 > 缺失字段 > 评分不达标 */
    private String issueType(ScoreResultVO vo) {
        // 1. 从重到轻取第一项命中：逻辑冲突 > 缺失字段 > 评分不达标
        //    只报一项是因为复核列表要按类型分组，混着报没法分派
        if (!vo.getLogicConflicts().isEmpty()) {
            return "逻辑冲突";
        }
        if (vo.getDeductions().stream().anyMatch(d -> "核心字段缺失".equals(d.getType()))) {
            return "缺失字段";
        }
        return "评分不达标";
    }

    /** 顺延 N 个工作日（跳过周末），用作复核时限 */
    private LocalDateTime addWorkdays(LocalDateTime start, int days) {
        LocalDateTime d = start;
        int added = 0;
        // 1. 逐日推进，只在工作日计数；周末不消耗额度
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
        // 1. 规则本体与加载告警直接来自 store，保证前端看到的和评分用的是同一份
        QcRulesVO vo = new QcRulesVO(ruleStore.get(), ruleStore.warnings());
        // 2. 自然语言描述与字段目录同源生成，规则一改文案跟着改
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
     * 触顶时置 {@code truncated} 标记并停止。等级分布按 {@code grade} 列直接统计，
     * 扣分数据优先读库内已存评分结果（{@code qc_results}），缺失则按当前规则现算，
     * 再按扣分类型与「类型|条目」两级聚合次数和分值。明细按分值降序取前 20。</p>
     *
     * <p><b>两段式扫描（§七 L4）</b>：主扫描只 SELECT {@code id / grade / qc_results} 三列
     * —— 原来的 {@code selectPage} 拉的是整行，含 {@code present_illness} /
     * {@code chief_complaint} 等 TEXT 大字段，40000 条会把这些列全部拉进堆里。
     * 只有「{@code qc_results} 为空」的记录才需要现算，而现算必须拿到
     * {@code structured_data} + 19 个原始列（{@code QcScorer} 的回退要用），
     * 故这些 id 收集起来、分批回查整行再算（慢路径，重算后通常几乎为空集）。
     * ⚠️ 不可「只选 3 列就直接现算」—— 那样未评分记录会被误判为「要素全缺失」而扣满分。</p>
     *
     * <p><b>已知边界</b>：导入后未重算时 {@code qc_results} 全为空 → 回退子集 = 全库，
     * 两段式退化为「全量回查」，收益归零（与改造前同慢）。重算后才几乎命中不到回退。
     * 若首屏仍慢，先跑一次重算（§七 L5），或给本接口加 60s Redis 缓存。</p>
     *
     * @param filters 用户筛选条件，可为 null（表示不限）
     * @return 类型 / 条目扣分聚合、等级分布、扫描条数与是否被截断
     */
    @Override
    public DeductionStatsVO deductionStats(FiltersDTO filters) {
        // 1. 构造数据域过滤条件（角色可见范围 + 用户筛选）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentGroupId(), filters);
        // 2. 主扫描只取 3 列（见方法注释的「两段式扫描」）
        wrapper.select("id", "grade", "qc_results");
        // 3. 初始化聚合容器：按类型、按条目、等级分布
        DeductionStatsVO vo = new DeductionStatsVO();
        Map<String, int[]> byType = new LinkedHashMap<>();
        Map<String, int[]> byItem = new LinkedHashMap<>();
        Map<String, Integer> gradeDist = new LinkedHashMap<>();
        // 4. 慢路径待回查的 id：库内没有可用的 qc_results
        List<String> fallbackIds = new ArrayList<>();
        int scanned = 0;
        int totalPoints = 0;
        int pageNo = 1;
        // 5. 分页扫描，每页 1000 条，最多扫 MAX_SCAN_RECORDS 条
        while (true) {
            Page<Record> page = baseMapper.selectPage(new Page<>(pageNo, BATCH_PAGE_SIZE), wrapper);
            List<Record> records = page.getRecords();
            if (records.isEmpty()) {
                break;
            }
            for (Record r : records) {
                // 5.1 触顶即置截断标记，停止本页剩余累计
                if (scanned >= MAX_SCAN_RECORDS) {
                    vo.setTruncated(true);
                    break;
                }
                // 5.2 计入扫描数并累计等级分布（未评分的归入「未评分」）
                scanned++;
                gradeDist.merge(r.getGrade() == null ? "未评分" : r.getGrade(), 1, Integer::sum);
                // 5.3 快路径：qc_results 命中即直接聚合，无需任何原始列
                ScoreResultVO sr = readStoredScore(r);
                if (sr != null) {
                    totalPoints += accumulate(sr, byType, byItem);
                } else {
                    // 5.4 慢路径：记下 id，等主扫描结束后回查整行再算
                    fallbackIds.add(r.getId());
                }
            }
            if (vo.isTruncated() || records.size() < BATCH_PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        // 6. 慢路径：分批回查整行（含 structured_data 与 19 个原始列）后现算并聚合
        for (int i = 0; i < fallbackIds.size(); i += FALLBACK_CHUNK) {
            List<String> chunk = fallbackIds.subList(i, Math.min(i + FALLBACK_CHUNK, fallbackIds.size()));
            List<Record> full = baseMapper.selectList(new QueryWrapper<Record>().select().in("id", chunk));
            for (Record r : full) {
                ScoreResultVO sr = scoreOf(r);
                if (sr != null && sr.getDeductions() != null) {
                    totalPoints += accumulate(sr, byType, byItem);
                }
            }
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
        // P5.2：明细被截断时告知前端，避免“为什么只看到 20 条”的隔屏疑问
        vo.setItemsTruncated(items.size() > 20);
        // 10. 回填扫描条数、总分与等级分布
        vo.setScanned(scanned);
        vo.setTotalPoints(totalPoints);
        vo.setGradeDist(gradeDist);
        return vo;
    }

    /**
     * 按「类型」与「类型|条目」两级累计扣分次数与分值。
     *
     * @return 本条记录贡献的总扣分
     */
    private int accumulate(ScoreResultVO sr, Map<String, int[]> byType, Map<String, int[]> byItem) {
        int points = 0;
        for (ScoreResultVO.Deduction d : sr.getDeductions()) {
            points += d.getPoints();
            int[] a = byType.computeIfAbsent(d.getType(), k -> new int[2]);
            a[0]++;
            a[1] += d.getPoints();
            int[] b = byItem.computeIfAbsent(d.getType() + "|" + d.getItem(), k -> new int[2]);
            b[0]++;
            b[1] += d.getPoints();
        }
        return points;
    }

    /**
     * 快路径：只读库内已存的 {@code qc_results}；没有或解析失败返回 {@code null}（交慢路径现算）。
     *
     * <p>单独拆出来是因为它<b>不需要任何原始列</b>，可以在「只 SELECT id/grade/qc_results」
     * 的窄扫描里跑完；慢路径 {@link #scoreOf} 则依赖 {@code structured_data} 与 19 个原始列，
     * 必须回查整行。</p>
     */
    private ScoreResultVO readStoredScore(Record r) {
        if (r.getQcResults() == null || r.getQcResults().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(r.getQcResults(), ScoreResultVO.class);
        } catch (Exception e) {
            // 落库格式异常则退回现算（走慢路径）；不抛是因为单条脏数据不该中断整批重算，
            // 但必须留痕：否则「脏数据有多少」在日志里完全不可见
            log.warn("[质控] 病历 {} 的 qc_results 解析失败，退回现算：{}", r.getId(), e.getMessage());
            return null;
        }
    }

    /** 取该病历的评分结果：优先读 qc_results，缺失则按当前规则现算（需整行 Record） */
    private ScoreResultVO scoreOf(Record r) {
        // 1. 优先读库内已存评分：历史统计要与当初的判定一致，不能按新规则重算
        ScoreResultVO stored = readStoredScore(r);
        if (stored != null) {
            return stored;
        }
        // 2. 没有存值就按当前规则现算；算不出来返回 null，由调用侧跳过这一条
        try {
            return QcScorer.score(asMap(null, r.getStructuredData()), r, false, ruleStore.get());
        } catch (Exception e) {
            // 现算失败：调用侧把该条当「算不出来」跳过。不抛，但必须留痕 ——
            // 静默 return null 会让「评分缺失」与「确实算不出」在上层完全无法区分
            log.warn("[质控] 病历 {} 现算评分失败，该条将不计分：{}", r.getId(), e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 按 ID 取病历；ID 为空返回 null（由上层转 400/404）。
     *
     * <p>§ 6.3 缺点 6：取到后验证组，不属于本组返回 null
     * （统一按不存在处理，不泄露“存在但看不到”）。</p>
     */
    private Record loadRaw(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return null;
        }
        Record r = baseMapper.selectById(recordId);
        return RecordFilter.canAccess(r) ? r : null;
    }

    /** 结构化数据：优先用入参对象，否则解析 JSON；空/坏数据返回 null 交由评分按"未结构化"处理 */
    private Map<String, Object> asMap(Object inline, String json) {
        // 1. 入参对象优先，JSON 文本兜底
        Object src = inline != null ? inline : json;
        if (src == null) {
            return null;
        }
        try {
            // 2. 文本走 JSON 解析，对象走类型转换
            if (src instanceof String s) {
                return s.isBlank() ? null : objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
                });
            }
            return objectMapper.convertValue(src, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 3. 解析失败返回 null，由评分按「未结构化」处理（会扣分），必须留痕：
            //    否则「病历没结构化」与「JSON 坏了」两种原因在结果里看不出区别
            log.warn("[质控] 结构化数据解析失败，按「未结构化」计分：{}", e.getMessage());
            return null;
        }
    }

    /** 该结构化字段是否非空（要素"有记录"的判据之一） */
    private boolean structuredPresent(String key, Map<String, Object> data) {
        // 只有「是列表且非空」才算要素有记录；空数组与缺键同义
        return key != null && data != null && data.get(key) instanceof List<?> list && !list.isEmpty();
    }

    /** 原始列里任一回退字段有值（判"漏抽"：病历写了但没被抽出来） */
    private boolean rawPresent(List<String> fields, Record raw) {
        // 1. 任一回退字段有值就算「病历写了」——用于区分真缺失与漏抽
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

    /** 格式规则是否通过：enum 看白名单，regex 匹表达式；表达式非法时放行（不算病历错） */
    private boolean formatOk(QcRuleSet.FormatRule fr, String v) {
        // 1. enum 规则看值是否在白名单里
        if ("enum".equalsIgnoreCase(fr.getType())) {
            return fr.getValues() != null && fr.getValues().contains(v);
        }
        // 2. 没配表达式就跳过校验
        if (fr.getExpr() == null || fr.getExpr().isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(fr.getExpr()).matcher(v).matches();
        } catch (Exception e) {
            // 3. 规则自身写错（表达式非法）时放行：宁可漏判也不能给病历记错
            return true;
        }
    }

    /** 按字段名取原始列值（空白视作无值），字段名由规则集配置 */
    private String rawValue(Record r, String field) {
        // 1. 字段名由规则集配置，null 字段或 null 病历都按无值处理
        if (r == null || field == null) {
            return null;
        }
        // 2. 按字段名映射到列；未配置的字段返回 null
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
        // 3. 空白视作无值，避免把空格当内容做存在性判断
        return v == null || v.isBlank() ? null : v.trim();
    }
}
