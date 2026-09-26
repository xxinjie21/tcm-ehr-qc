package com.tcm.ehr.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
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

    @Value("${qc.rules-file:data/qc-rules.json}")
    private String rulesFile;

    private volatile QcRuleSet current;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    public QcRuleStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 字段注入完成后再加载（@Value 尚未生效时不能读 rulesFile） */
    @PostConstruct
    void init() {
        this.current = loadWithMerge();
    }

    /** 当前生效规则 */
    public QcRuleSet get() {
        return current;
    }

    /** 最近一次加载/保存的告警（供前端提示） */
    public List<String> warnings() {
        return new ArrayList<>(warnings);
    }

    /** 覆盖并落盘，返回生效规则 */
    public QcRuleSet update(QcRuleSet next) {
        QcRuleSet normalized = normalize(next == null ? QcRuleSet.defaults() : next);
        persist(normalized);
        this.current = normalized;
        log.info("[质控规则] 已更新并落盘：{}", rulesFile);
        return current;
    }

    /** 恢复默认（删除文件 + 用内置默认） */
    public QcRuleSet reset() {
        try {
            Path f = Paths.get(rulesFile);
            Files.deleteIfExists(f);
        } catch (Exception e) {
            log.warn("[质控规则] 删除规则文件失败: {}", e.getMessage());
        }
        warnings.clear();
        this.current = QcRuleSet.defaults();
        return current;
    }

    // ------------------------------------------------------------------ 内部

    /** 默认 → 文件覆盖 → 归一校验 */
    private QcRuleSet loadWithMerge() {
        Map<String, Object> merged = toMap(QcRuleSet.defaults());
        Map<String, Object> fromFile = readFile();
        if (fromFile != null) {
            deepMerge(merged, fromFile);
        }
        QcRuleSet rules;
        try {
            rules = mapper.convertValue(merged, QcRuleSet.class);
        } catch (Exception e) {
            warnings.add("规则文件解析失败，已回退默认：" + e.getMessage());
            rules = QcRuleSet.defaults();
        }
        return normalize(rules);
    }

    private Map<String, Object> readFile() {
        try {
            Path f = Paths.get(rulesFile);
            if (!Files.exists(f)) {
                return null;
            }
            return mapper.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            warnings.add("读取规则文件失败，已用默认：" + e.getMessage());
            return null;
        }
    }

    private void persist(QcRuleSet rules) {
        try {
            Path f = Paths.get(rulesFile);
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), rules);
        } catch (Exception e) {
            log.warn("[质控规则] 落盘失败（本次仅内存生效）: {}", e.getMessage());
        }
    }

    /** 关键项兜底：空标准/非法阈值一律回默认并告警（不允许空标准导致人人满分） */
    private QcRuleSet normalize(QcRuleSet r) {
        warnings.clear();
        if (r.getCompleteness() == null || r.getCompleteness().getElements() == null
                || r.getCompleteness().getElements().isEmpty()) {
            warnings.add("完整性要素为空，已回填内置 6 要素");
            r.setCompleteness(QcRuleSet.defaults().getCompleteness());
        }
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
        if (r.getStandardization() == null) {
            r.setStandardization(QcRuleSet.defaults().getStandardization());
        }
        QcRuleSet.Thresholds t = r.getThresholds();
        if (t == null || t.getQualified() <= t.getInvalid() || t.getInvalid() < 0 || t.getSeriousFullMissing() < 1) {
            warnings.add("分级阈值非法，已回默认 90/60/3");
            r.setThresholds(new QcRuleSet.Thresholds());
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> base, Map<String, Object> override) {
        for (Map.Entry<String, Object> e : override.entrySet()) {
            Object bv = base.get(e.getKey());
            Object ov = e.getValue();
            if (bv instanceof Map && ov instanceof Map) {
                deepMerge((Map<String, Object>) bv, (Map<String, Object>) ov);
            } else if (ov != null) {
                base.put(e.getKey(), ov);
            }
        }
    }

    private Map<String, Object> toMap(Object o) {
        try {
            return mapper.convertValue(o, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
