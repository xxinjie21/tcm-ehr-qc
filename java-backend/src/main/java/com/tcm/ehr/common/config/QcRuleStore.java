package com.tcm.ehr.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import com.tcm.ehr.domain.po.QcRule;
import com.tcm.ehr.mapper.QcRuleMapper;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质控规则存储：启动加载 {@code qc-rules.json} 并与内置默认<b>深合并</b>；
 * 读写单一数据源，保存即生效。
 *
 * <p>兜底语义：文件缺失 → 用内置默认；字段缺失 → 用默认补；单条非法 → 只影响该条；
 * 完整性要素为空 / 阈值非法 → 回默认并记录告警（不允许"空标准"导致人人满分）。</p>
 */
@Slf4j
@Component
public class QcRuleStore {

    private final ObjectMapper mapper;
    /** 组织级规则（每组织一行；无记录回退内置默认） */
    private final QcRuleMapper ruleMapper;

    @Value("${qc.rules-file:data/qc-rules.json}")
    private String rulesFile;

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

    /** 字段注入完成后再加载（@Value 尚未生效时不能读 rulesFile） */
    @PostConstruct
    void init() {
        this.current = loadWithMerge();
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
            return reset();
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

    /** 覆盖并落盘，返回生效规则 */
    public QcRuleSet update(QcRuleSet next) {
        // 1. 先归一（补齐关键项），再落盘、换内存 —— 顺序不能反：
        //    落盘的必须是校验过的版本，否则重启会读到残缺规则
        QcRuleSet normalized = normalize(next == null ? QcRuleSet.defaults() : next);
        persist(normalized);
        this.current = normalized;
        log.info("[质控规则] 已更新并落盘：{}", rulesFile);
        return current;
    }

    /** 恢复默认（删除文件 + 用内置默认） */
    public QcRuleSet reset() {
        // 1. 删规则文件：否则重启后又会被它覆盖回来
        try {
            Path rulesPath = Paths.get(rulesFile);
            Files.deleteIfExists(rulesPath);
        } catch (Exception e) {
            log.warn("[质控规则] 删除规则文件失败: {}", e.getMessage());
        }
        // 2. 清告警并换回内置默认
        warnings.clear();
        this.current = QcRuleSet.defaults();
        return current;
    }

    // ------------------------------------------------------------------ 内部

    /** 默认 → 文件覆盖 → 归一校验 */
    private QcRuleSet loadWithMerge() {
        // 1. 以默认规则为底
        Map<String, Object> merged = toMap(QcRuleSet.defaults());
        // 2. 有文件就深合并（文件里只写要改的字段，缺失的沿用默认）
        Map<String, Object> fromFile = readFile();
        if (fromFile != null) {
            deepMerge(merged, fromFile);
        }
        QcRuleSet rules;
        try {
            rules = mapper.convertValue(merged, QcRuleSet.class);
        } catch (Exception e) {
            // 3. 解析不了就整份回默认：半份规则比默认更危险
            warnings.add("规则文件解析失败，已回退默认：" + e.getMessage());
            rules = QcRuleSet.defaults();
        }
        // 4. 最后统一归一校验
        return normalize(rules);
    }

    private Map<String, Object> readFile() {
        try {
            Path rulesPath = Paths.get(rulesFile);
            // 1. 文件不存在是正常状态（从未改过规则）
            if (!Files.exists(rulesPath)) {
                return null;
            }
            return mapper.readValue(rulesPath.toFile(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            // 2. 坏了记告警并按"无文件"处理，服务照常起
            warnings.add("读取规则文件失败，已用默认：" + e.getMessage());
            return null;
        }
    }

    private void persist(QcRuleSet rules) {
        try {
            // 1. 原子写：先写同目录临时文件再 move（直接 writeValue 写到一半会留下半截 JSON，
            //    下次启动读它解析失败就无声回退默认，且原内容已被截断）
            // 2. 美化输出，便于管理员手工核对规则内容
            com.tcm.ehr.common.utils.AtomicJsonWriter.write(mapper, Paths.get(rulesFile), rules);
        } catch (Exception e) {
            // 3. 落盘失败只告警：内存里已生效，不该让保存操作整个失败
            log.warn("[质控规则] 落盘失败（本次仅内存生效）: {}", e.getMessage());
        }
    }

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

    /** 递归深合并：override 覆盖 base，缺字段保留默认（实现"字段级补默认"） */
    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> base, Map<String, Object> override) {
        // 1. 逐 key 处理覆盖项
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object bv = base.get(e.getKey());
            Object ov = e.getValue();
            // 2. 两边都是对象 → 递归合并（保留 base 里 override 没提到的字段）
            if (bv instanceof Map && ov instanceof Map) {
                deepMerge((Map<String, Object>) bv, (Map<String, Object>) ov);
            } else if (ov != null) {
                // 3. 标量直接覆盖；override 里显式给 null 视为"没给"，保留默认
                base.put(e.getKey(), ov);
            }
        }
    }

    /** 规则对象转 Map（供深合并用）；转换失败返回空 map */
    private Map<String, Object> toMap(Object o) {
        try {
            return mapper.convertValue(o, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            // 转换失败给空 map：深合并会退化成"只用文件内容"，后续 normalize 再兜底
            return new LinkedHashMap<>();
        }
    }
}
