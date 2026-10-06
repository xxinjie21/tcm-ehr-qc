package com.tcm.ehr.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.tcm.ehr.domain.po.QcRule;
import com.tcm.ehr.mapper.QcRuleMapper;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 质控规则存储：每组织一行（{@code qc_rules} 表），无行的组织回退内置默认；
 * 保存即生效，跨实例以 DB 为准。
 *
 * <p>兜底语义：组织无行 / 组织行解析失败 → 用内置默认；字段缺失 → 用默认补；
 * 单条非法 → 只影响该条；完整性要素为空 / 阈值非法 → 回默认并记录告警
 * （不允许"空标准"导致人人满分）。</p>
 *
 * <p>批次 25.a：原先的 {@code qc-rules.json} 文件兜底已删除 —— 该文件从来不存在
 * （{@code application.yml} 也没有配置项），写路径 {@code update()}/{@code reset()}
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
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    public QcRuleStore(ObjectMapper mapper, QcRuleMapper ruleMapper) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
    }

    /** 启动时把内置默认加载为系统基线（无文件、无外部依赖） */
    @PostConstruct
    void init() {
        this.current = normalize(QcRuleSet.defaults());
    }

    /** 当前生效规则 */
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
        QcRule row = ruleMapper.selectById(orgId);
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
            ruleMapper.insert(row);
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
     * <p><b>不为 null 兜底</b>：{@code current} 只在 {@code init()}（{@code @PostConstruct}）里赋值，
     * 容器尚未完成启动或单测直接 new 时它仍是 null。直接把 null 传下去，
     * 会让归一/评分链路在「规则还没加载」时踩 NPE。</p>
     */
    private QcRuleSet baseline() {
        QcRuleSet c = get();
        return c != null ? c : QcRuleSet.defaults();
    }

    public QcRuleSet get() {
        return current;
    }

    /** 最近一次加载/保存的告警（供前端提示） */
    /** 规则版本自增：让 LlmClient 之类的「按版本判断是否重建」逻辑感知变化 */
    private void bump() {
        version.incrementAndGet();
    }

    /** 写路径改完必须显式失效该组织的缓存：版本串里的 update_time 只到秒，同秒两次写会撞上 */
    private void invalidate(String orgId) {
        perOrg.invalidate(orgId);
    }

    public List<String> warnings() {
        return new ArrayList<>(warnings);
    }

    // ------------------------------------------------------------------ 内部

    /** 关键项兜底：空标准/非法阈值一律回默认并告警（不允许空标准导致人人满分） */
    private QcRuleSet normalize(QcRuleSet r) {
        // 1. 完整性要素为空 → 回填内置 6 要素
        warnings.clear();
        if (r.getCompleteness() == null || r.getCompleteness().getElements() == null
                || r.getCompleteness().getElements().isEmpty()) {
            warnings.add("完整性要素为空，已回填内置 6 要素");
            r.setCompleteness(QcRuleSet.defaults().getCompleteness());
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
                    warnings.add("存在旧版/无效的一致性规则，已忽略：" + (c.getName() == null ? "(未命名)" : c.getName()));
                }
            }
            r.setConsistency(valid);
        }
        if (r.getFormat() == null) {
            r.setFormat(new ArrayList<>());
        }
        // 3. 标准化配置缺失 → 回默认
        if (r.getStandardization() == null) {
            r.setStandardization(QcRuleSet.defaults().getStandardization());
        }
        // 4. 分级阈值非法（合格线 ≤ 无效线等）→ 回默认并告警
        QcRuleSet.Thresholds t = r.getThresholds();
        if (t == null || t.getQualified() <= t.getInvalid() || t.getInvalid() < 0 || t.getSeriousFullMissing() < 1) {
            warnings.add("分级阈值非法，已回默认 90/60/3");
            r.setThresholds(new QcRuleSet.Thresholds());
        }
        return r;
    }

}
