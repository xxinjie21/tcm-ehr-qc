package com.tcm.ehr.common.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.tcm.ehr.domain.po.QcRule;
import com.tcm.ehr.mapper.QcRuleMapper;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 质控规则存储：每组织一行（qc_rules 表），无行的组织回退内置默认；
 * 保存即生效，跨实例以 DB 为准。
 *
 * <p>兜底语义：组织无行 / 组织行解析失败 → 用内置默认；字段缺失 → 用默认补；
 * 单条非法 → 只影响该条；完整性要素为空 / 阈值非法 → 回默认并记录告警
 * （不允许"空标准"导致人人满分）。</p>
 *
 * <p>批次 25.a：原先的 {@code qc-rules.json} 文件兜底已删除 —— 该文件从来不存在
 * （application.yml 也没有配置项），写路径 {@code update()}/{@code reset()}
 * 无生产调用方，规则权威自批次 17 起就是 DB。</p>
 */
@Slf4j
@Component
public class QcRuleStore {

    private final ObjectMapper mapper;
    /** 组织级规则（每组织一行；无记录回退内置默认） */
    private final QcRuleMapper ruleMapper;

    private volatile QcRuleSet current;

    private final java.util.concurrent.atomic.AtomicLong version =
            new java.util.concurrent.atomic.AtomicLong();
    /** 组织级规则的解析结果缓存（版本一致就复用，避免批任务逐条重新解析 rules_json） */
    private final com.tcm.ehr.common.utils.VersionedCache<QcRuleSet> perOrg =
            new com.tcm.ehr.common.utils.VersionedCache<>(64);
    /** 组织规则行的短 TTL（T14）：5s 内不重复 selectById；写路径主动清 */
    private static final long ROW_CACHE_TTL_MS = 5_000L;
    /**
     * 组织规则行缓存（T14）：写后 5s 过期，写路径 {@link #invalidate} 主动失效。
     *
     * <p><b>值用 {@link Optional} 包一层</b>：Caffeine 不接受 null 值（映射函数返回 null 时不写缓存），
     * 而「该组织没有行」是稳定事实、同样值得短缓存 —— 直接存 QcRule 会让无行组织退化成
     * 每次 selectById（批量质控 4 万条 = 4 万次查询，正是 T14 要消掉的开销）。</p>
     *
     * <p><b>刻意不设 maximumSize</b>：键空间 = 组织数（很小）。加上界会引入当前没有的逐出，
     * 让本可命中的查询多打一次库，与「零行为改动」冲突。原实现也无上界。</p>
     */
    private final Cache<String, Optional<QcRule>> rowCache = Caffeine.newBuilder()
            .expireAfterWrite(ROW_CACHE_TTL_MS, TimeUnit.MILLISECONDS)
            .build();
    /** 最近一次 normalize 的告警（不可变快照，整体替换；见 26.10） */
    private volatile List<String> warnings = List.of();

    public QcRuleStore(ObjectMapper mapper, QcRuleMapper ruleMapper) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
    }

    /** 启动时把内置默认加载为系统基线（无文件、无外部依赖） */
    @PostConstruct
    void init() {
        this.current = normalize(QcRuleSet.defaults());
    }

    /**
     * 取<b>某个组织</b>生效的规则：该组织有行就用它的，没有则回退内置默认。
     *
     * <p>回退默认时<b>不落库</b>：给所有组织都写一份默认行没意义，
     * 还会在默认规则升级时留下一堆过期的行。</p>
     *
     * @param orgId 组织 ID；空值表示无组织上下文，回退默认
     */
    public QcRuleSet getFor(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return baseline();
        }
        QcRule row = rowFor(orgId);
        if (row == null || row.getRulesJson() == null || row.getRulesJson().isBlank()) {
            return baseline();
        }
        // 版本没变就复用上次解析好的规则集：批任务逐条调用 getFor，每次重新解析 rules_json
        // 是纯浪费。版本串并入进程内计数器（本实例任何一次写入都会推进它），
        // 再并上 update_time 兜住「同秒内两处写」与「另一个实例改了库」。
        String v = version.get() + ":"
                + (row.getUpdateTime() == null ? "" : row.getUpdateTime().toString());
        return perOrg.get(orgId, v, () -> parseOrBaseline(orgId, row));
    }

    /**
     * 取<b>某个组织</b>参与「术语归一」的类型集合（normalization.elementTypes）。
     *
     * <p>归一执行链路（EntityNormalizer / GovernanceServiceImpl）据此判断
     * 某类型要不要做词形归一；QcScorer 另用它把扣分范围收窄为「质控勾选 ∩ 归一勾选」。</p>
     *
     * <p>规则行经 {@link #getFor(String)} 解析时已被 {@link #normalize(QcRuleSet)} 回填默认，
     * 故这里只在极端情形（外部直接构造出缺字段的规则集）兜底。</p>
     *
     * @param orgId 组织 ID；空值回退系统基线（默认 9 类）
     * @return 类型 key 集合；<b>空集表示该组织选择「全部不归一」</b>
     */
    public java.util.Set<String> normalizationTypes(String orgId) {
        QcRuleSet r = getFor(orgId);
        QcRuleSet.Normalization n = r == null ? null : r.getNormalization();
        if (n == null || n.getElementTypes() == null) {
            return java.util.Set.copyOf(QcRuleSet.DEFAULT_NORMALIZATION_TYPES);
        }
        return java.util.Set.copyOf(n.getElementTypes());
    }

    /**
     * 取组织规则行，5 秒内复用同一行（T14）。
     *
     * <p>只缓存解析结果是不够的：版本串要由行本身（update_time）算出来，
     * 所以每次 getFor 仍要 selectById —— 批量质控 / 批解析逐条调用时
     * 4 万条就是 4 万次查询。这里给「行」加一层短 TTL，与
     * DictionaryTermStoreImpl 的 cachedVersion 同一范式：
     * 靠短 TTL 兜住跨实例变更，写路径<b>主动删 key</b> 保证本实例立即生效。</p>
     */
    private QcRule rowFor(String orgId) {
        // 命中（且未过期）直接返回；未命中或已过期才回源并写入。Caffeine 不会把过期条目返回，
        // 与原先「读时比对 expiresAtMs」同一效果。
        return rowCache.get(orgId, k -> Optional.ofNullable(ruleMapper.selectById(k)))
                .orElse(null);
    }

    /** 解析某组织的规则 JSON；坏数据回退默认并留告警（存量坏数据不该让质检停摆） */
    private QcRuleSet parseOrBaseline(String orgId, QcRule row) {
        try {
            QcRuleSet parsed = mapper.readValue(row.getRulesJson(), QcRuleSet.class);
            return normalize(parsed);
        } catch (Exception e) {
            log.warn("[质控规则] 组织 {} 的规则解析失败，回退内置默认：{}", orgId, e.getMessage());
            return baseline();
        }
    }

    /**
     * 保存<b>某个组织</b>的规则。
     *
     * @param orgId  组织 ID
     * @param next   规则集
     * @return 保存后的规则集
     */
    public QcRuleSet updateFor(String orgId, QcRuleSet next) {
        if (orgId == null || orgId.isBlank()) {
            throw new IllegalArgumentException("缺少组织上下文，无法保存质控规则");
        }
        QcRuleSet normalized = normalize(next);
        // A8：写路径校验 format 规则的正则可编译。QcScorer 执行非法正则时是「静默放行」
        // （该项不判），规则会静默失效且配置的人看不到原因 —— 存不进去比存进去不生效好。
        // 刻意不放进 normalize()：它同时被读路径 parseOrBaseline 调用，放那里会给每次读
        // 都加一遍正则编译，且存量非法规则会在读时被静默丢弃。
        validateRegexRules(normalized);
        QcRule row = ruleMapper.selectById(orgId);
        boolean isNew = row == null;
        if (isNew) {
            row = new QcRule();
            row.setOrgId(orgId);
        }
        try {
            row.setRulesJson(mapper.writeValueAsString(normalized));
        } catch (Exception e) {
            // 存不进去就不能当成保存成功：回 500 比「提示已保存、实际没存」好
            throw new IllegalStateException("质控规则序列化失败，未保存", e);
        }
        row.setUpdateTime(java.time.LocalDateTime.now().withNano(0));
        if (isNew) {
            try {
                ruleMapper.insert(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // B6：check-then-act 的窗口 —— 并发下两个请求都判定「该组织无行」并同时 insert，
                // 一个撞主键。撞键说明行已被建好，退化为更新（内容以本次提交为准）。
                log.warn("[质控规则] 组织 {} 的规则行并发创建撞主键，改为更新", orgId);
                ruleMapper.updateById(row);
            }
        } else {
            ruleMapper.updateById(row);
        }
        bump();
        invalidate(orgId);
        return normalized;
    }

    /** 恢复某组织的内置默认规则（删掉该组织那一行） */
    public QcRuleSet resetFor(String orgId) {
        if (orgId == null || orgId.isBlank()) {
            // 无组织上下文没有可删的行：返回系统基线即可
            return baseline();
        }
        ruleMapper.deleteById(orgId);
        bump();
        invalidate(orgId);
        return baseline();
    }

    /**
     * 系统基线规则。
     *
     * <p><b>不为 null 兜底</b>：current 只在 {@code init()}（{@code @PostConstruct}）里赋值，
     * 容器尚未完成启动或单测直接 new 时它仍是 null。直接把 null 传下去，
     * 会让归一/评分链路在「规则还没加载」时踩 NPE。</p>
     */
    private QcRuleSet baseline() {
        QcRuleSet c = get();
        return c != null ? c : QcRuleSet.defaults();
    }

    /** 当前生效规则（仅类内 {@link #baseline()} 消费） */
    private QcRuleSet get() {
        return current;
    }

    /** 规则版本自增：让 LlmClient 之类的「按版本判断是否重建」逻辑感知变化 */
    private void bump() {
        version.incrementAndGet();
    }

    /** 写路径改完必须显式失效该组织的缓存：版本串里的 update_time 只到秒，同秒两次写会撞上 */
    private void invalidate(String orgId) {
        perOrg.invalidate(orgId);
        // 行缓存也要清：否则本实例在 5s 内仍读到旧行（写后立即读是保存接口的常规路径）
        rowCache.invalidate(orgId);
    }

    public List<String> warnings() {
        return new ArrayList<>(warnings);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 校验 format 规则里的正则可编译（A8）。
     *
     * <p><b>为什么要拦</b>：QcScorer.formatOk 对编译失败的正则是 catch 后
     * 「该项不判」—— 规则静默失效，界面上没有任何提示，配置的人以为生效了。
     * 写路径直接拒绝（全局处理器映射 <b>400</b>），当场告诉他是哪条规则写错了。</p>
     *
     * <p><b>只校验 type=regex 且 expr 非空白</b>：{@code type=enum} 走白名单不看 expr；
     * expr 为空表示「该项不判」，是合法配置（formatOk 里同样先返回 true）。</p>
     *
     * <p><b>不做</b>正则超时 / 回溯上限：「合法但灾难性回溯」的正则编译是能过的，
     * 要拦得换引擎（如 RE2J），属新增依赖与算法替换。写入者已是管理员 / owner 高信任档。</p>
     *
     * @throws IllegalArgumentException 存在无法编译的正则
     */
    private static void validateRegexRules(QcRuleSet r) {
        if (r == null || r.getFormat() == null) {
            return;
        }
        for (QcRuleSet.FormatRule fr : r.getFormat()) {
            if (fr == null || "enum".equalsIgnoreCase(fr.getType())) {
                continue;
            }
            String expr = fr.getExpr();
            if (expr == null || expr.isBlank()) {
                continue;
            }
            try {
                java.util.regex.Pattern.compile(expr);
            } catch (RuntimeException e) {
                String label = fr.getLabel() == null || fr.getLabel().isBlank()
                        ? fr.getField() : fr.getLabel();
                throw new IllegalArgumentException(
                        "格式规则「" + label + "」的正则无法编译：" + e.getMessage());
            }
        }
    }

    /** 关键项兜底：空标准/非法阈值一律回默认并告警（不允许空标准导致人人满分） */
    private QcRuleSet normalize(QcRuleSet r) {
        // 1. 完整性要素为空 → 回填内置 6 要素
        // 26.10：告警先累积到局部列表，最后整体替换 volatile 字段，
        // 避免并发 normalize 互相 clear/覆盖成半个列表（仅影响提示文案，但旧写法会丢告警）
        List<String> w = new ArrayList<>();
        if (r.getCompleteness() == null || r.getCompleteness().getElements() == null
                || r.getCompleteness().getElements().isEmpty()) {
            w.add("完整性要素为空，已回填内置 6 要素");
            r.setCompleteness(QcRuleSet.defaultCompleteness());
        }
        // 2. 过滤旧格式/不完整的一致性规则（缺触发或期望值 → 丢弃并告警）
        if (r.getConsistency() == null) {
            r.setConsistency(new ArrayList<>());
        } else {
            // 过滤旧格式/非法条目
            List<QcRuleSet.ConsistencyRule> valid = new ArrayList<>();
            for (QcRuleSet.ConsistencyRule c : r.getConsistency()) {
                boolean ok = c.getTriggerType() != null && c.getExpectType() != null
                        && c.getTriggerValues() != null && !c.getTriggerValues().isEmpty()
                        && c.getExpectValues() != null && !c.getExpectValues().isEmpty();
                if (ok) {
                    valid.add(c);
                } else {
                    w.add("存在旧版/无效的一致性规则，已忽略：" + (c.getName() == null ? "(未命名)" : c.getName()));
                }
            }
            r.setConsistency(valid);
        }
        if (r.getFormat() == null) {
            r.setFormat(new ArrayList<>());
        }
        // 3. 标准化配置缺失 → 回默认；elementTypes 为 null → 回默认 5 类。
        //    （空列表是用户「一个都不勾」的合法选择，保留为空、不参与评分，不回填）
        if (r.getStandardization() == null) {
            r.setStandardization(QcRuleSet.defaultStandardization());
        } else if (r.getStandardization().getElementTypes() == null) {
            r.getStandardization().setElementTypes(
                    new ArrayList<>(QcRuleSet.defaultStandardization().getElementTypes()));
        }
        // 3b. 归一范围缺失 → 回默认 9 类（空列表是用户「一个都不勾」的合法选择，保留为空、不回填）。
        //     与 standardization 同法：老行没有 normalization 字段（新加），回填默认即维持旧行为。
        if (r.getNormalization() == null) {
            r.setNormalization(QcRuleSet.defaultNormalization());
        } else if (r.getNormalization().getElementTypes() == null) {
            r.getNormalization().setElementTypes(
                    new ArrayList<>(QcRuleSet.DEFAULT_NORMALIZATION_TYPES));
        }
        // 4. 分级阈值非法（合格线 ≤ 无效线等）→ 回默认并告警
        QcRuleSet.Thresholds t = r.getThresholds();
        if (t == null || t.getReviewUpper() <= t.getInvalid() || t.getInvalid() < 0) {
            w.add("分级阈值非法，已回默认 80/60");
            r.setThresholds(new QcRuleSet.Thresholds());
        }
        this.warnings = List.copyOf(w);
        return r;
    }

}
