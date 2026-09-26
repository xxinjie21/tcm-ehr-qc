package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.StatsDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.OverviewVO;
import com.tcm.ehr.domain.vo.StatsAllVO;
import com.tcm.ehr.domain.vo.StatsVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.IStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计服务实现：指标卡聚合 + 按type统计（500条内内存聚合）+ 看板扩展（批C·4.2）。
 *
 * <p>看板扩展的过滤统一走 {@link RecordFilter}（先数据域、后用户筛选），
 * 与病历读取同域；审核员只统计待复核域。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsServiceImpl extends ServiceImpl<RecordMapper, Record> implements IStatsService {

    /** 评分分布桶（固定顺序） */
    private static final List<String> SCORE_BUCKETS = List.of("90+", "80-89", "70-79", "60-69", "60以下");

    private final ObjectMapper objectMapper;
    private final IDictionaryFileService fileService;

    @Override
    public List<String> departments() {
        return baseMapper.selectDepartments(domainGrade());
    }

    @Override
    public OverviewVO overview() {
        Map<String, Object> row = baseMapper.selectOverview(domainGrade());
        OverviewVO vo = new OverviewVO();
        vo.setTotalRecords(num(row.get("totalRecords")));
        vo.setQualifiedCount(num(row.get("qualifiedCount")));
        vo.setQualifiedRate(vo.getTotalRecords() == 0 ? 0.0
                : Math.round(vo.getQualifiedCount() * 1000.0 / vo.getTotalRecords()) / 10.0);
        vo.setPendingReviewCount(num(row.get("pendingReviewCount")));
        vo.setInvalidCount(num(row.get("invalidCount")));
        return vo;
    }

    /**
     * 三个入口都先过数据域，再叠加各自的圈定方式。
     *
     * <p>原实现三条路都绕开了 {@link RecordFilter}：按 ID 的 {@code selectBatchIds}、
     * 按筛选的 {@code filterByFilters}（先全表载入再内存过滤）、以及无条件的
     * {@code selectList(null)} —— 而 {@code /api/stats} 是「登录即可」，审核员借此能读到
     * 非待复核域病历的证型 / 方剂词频。</p>
     */
    @Override
    public StatsVO stats(StatsDTO dto) {
        String role = RequestUtils.currentRole();
        QueryWrapper<Record> wrapper;
        if (dto.getRecordIds() != null && !dto.getRecordIds().isEmpty()) {
            // 优先：按病历ID圈定（文档契约）；ID 由调用方给，因此数据域必须先加
            wrapper = RecordFilter.build(role, new FiltersDTO()).in("id", dto.getRecordIds());
        } else if (dto.getFilters() != null && !dto.getFilters().isEmpty()) {
            // 次选：按筛选条件（department/dateRange/pattern，复用查询1字段）
            wrapper = RecordFilter.build(role, toFilters(dto.getFilters()));
        } else {
            wrapper = RecordFilter.build(role, new FiltersDTO());
        }
        return statsFor(baseMapper.selectList(wrapper), dto.getType());
    }

    @Override
    public StatsAllVO all(FiltersDTO filters) {
        List<Record> records = recordsFor(filters);
        StatsAllVO vo = new StatsAllVO();
        vo.setOverview(overview());
        vo.setDisease(statsFor(records, "disease"));
        vo.setSymptom(statsFor(records, "symptom"));
        vo.setPattern(statsFor(records, "pattern"));
        vo.setPrescription(statsFor(records, "prescription"));
        return vo;
    }

    @Override
    public StatsVO extra(FiltersDTO filters) {
        List<Record> records = recordsFor(filters);
        StatsVO vo = new StatsVO();

        // ① 质控趋势：按就诊月份聚合（升序，最多最近 12 个月）
        TreeMap<String, long[]> byMonth = new TreeMap<>();
        for (Record r : records) {
            String month = monthOf(r);
            if (month == null) continue;
            long[] acc = byMonth.computeIfAbsent(month, k -> new long[3]); // total, qualified, pending
            acc[0]++;
            if (isGrade(r, "合格")) acc[1]++;
            else if (isGrade(r, "待复核")) acc[2]++;
        }
        List<Map.Entry<String, long[]>> months = new java.util.ArrayList<>(byMonth.entrySet());
        if (months.size() > 12) {
            months = months.subList(months.size() - 12, months.size());
        }
        for (Map.Entry<String, long[]> e : months) {
            StatsVO.TrendPoint p = new StatsVO.TrendPoint();
            p.setMonth(e.getKey());
            p.setTotal(e.getValue()[0]);
            p.setQualified(e.getValue()[1]);
            p.setQualifiedRate(rate(e.getValue()[1], e.getValue()[0]));
            p.setPendingReview(e.getValue()[2]);
            vo.getTrend().add(p);
        }

        // ② 科室合格率
        Map<String, long[]> byDept = new HashMap<>();
        for (Record r : records) {
            String dept = r.getDepartment() == null || r.getDepartment().isBlank() ? "未填科室" : r.getDepartment().trim();
            long[] acc = byDept.computeIfAbsent(dept, k -> new long[2]); // total, qualified
            acc[0]++;
            if (isGrade(r, "合格")) acc[1]++;
        }
        byDept.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .forEach(e -> {
                    StatsVO.DeptRate d = new StatsVO.DeptRate();
                    d.setDepartment(e.getKey());
                    d.setTotal(e.getValue()[0]);
                    d.setQualified(e.getValue()[1]);
                    d.setQualifiedRate(rate(e.getValue()[1], e.getValue()[0]));
                    vo.getDepartmentRates().add(d);
                });

        // ③ 评分分布直方图（固定桶顺序）
        Map<String, Long> dist = new LinkedHashMap<>();
        SCORE_BUCKETS.forEach(b -> dist.put(b, 0L));
        for (Record r : records) {
            if (r.getScore() == null) continue;
            dist.merge(bucketOf(r.getScore()), 1L, Long::sum);
        }
        dist.forEach((bucket, count) -> {
            StatsVO.Bucket b = new StatsVO.Bucket();
            b.setBucket(bucket);
            b.setCount(count);
            vo.getScoreDistribution().add(b);
        });

        // ④ 词典规模（5 类术语数量）：读词典文件，见 termCount
        vo.getDictionary().put("disease", termCount("disease"));
        vo.getDictionary().put("symptom", termCount("symptom"));
        vo.getDictionary().put("pattern", termCount("pattern"));
        vo.getDictionary().put("herb", termCount("herb"));
        vo.getDictionary().put("formula", termCount("formula"));
        return vo;
    }

    /**
     * 某类术语条数：直接读词典 JSON 文件（原先读 {@code DictionaryStore} 的内存缓存）。
     *
     * <p>DictionaryStore 已随「归一不再内存兜底」删除，这里改读同一个数据源 ——
     * <b>JSON 文件才是词典的真源</b>，ES 只是它的检索副本。刻意<b>不</b>改成查 ES 的 _count：
     * 那样看板会因 ES 不可用而连「词典规模」这种静态信息都拿不到，而改造前看板并不依赖 ES。</p>
     *
     * <p>读文件失败时返回 <b>-1</b> 而不是 0 —— 0 会被读成「词典是空的」，属于另一种误导。</p>
     */
    private int termCount(String type) {
        try {
            return fileService.read(type).size();
        } catch (IOException e) {
            log.warn("[统计] {} 词典读取失败，词条数按 -1 上报: {}", type, e.getMessage());
            return -1;
        }
    }

    /** 按数据域 + 用户筛选取病历（看板扩展口径） */
    private List<Record> recordsFor(FiltersDTO filters) {
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(), filters);
        return baseMapper.selectList(wrapper);
    }

    private StatsVO statsFor(List<Record> records, String type) {
        StatsVO vo = new StatsVO();
        switch (type == null ? "" : type) {
            case "disease" -> vo.setStatistics(top(agg(records, "diseases", "disease"), 10, "disease"));
            case "pattern" -> vo.setDistribution(top(agg(records, "patternList", "pattern"), 10, "pattern"));
            case "symptom" -> vo.setStatistics(top(agg(records, "symptoms", "symptom"), 10, "symptom"));
            case "prescription" -> {
                vo.setFormulaStats(top(agg(records, "formulaList", "formula"), 10, "formula"));
                vo.setHerbStats(top(agg(records, "herbs", "herb"), 10, "herb"));
            }
            default -> throw new IllegalArgumentException("type必须为disease/pattern/symptom/prescription");
        }
        return vo;
    }

    private static String monthOf(Record r) {
        if (r.getVisitTime() == null) return null;
        String s = r.getVisitTime().toString();
        return s.length() >= 7 ? s.substring(0, 7) : null;
    }

    private static boolean isGrade(Record r, String grade) {
        return grade.equals(r.getGrade());
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static String bucketOf(int score) {
        if (score >= 90) return "90+";
        if (score >= 80) return "80-89";
        if (score >= 70) return "70-79";
        if (score >= 60) return "60-69";
        return "60以下";
    }

    /** 本请求的数据域分级：审核员 = 待复核，管理员 = null（不限） */
    private String domainGrade() {
        return RecordFilter.domainGrade(RequestUtils.currentRole());
    }

    /**
     * stats 契约里的 filters 是无类型的 {@code Map}，这里翻译成 {@link FiltersDTO}
     * 再交给 {@link RecordFilter} —— 条件组装口径只保留一处，不再有第二套内存过滤。
     */
    private FiltersDTO toFilters(Map<String, Object> filters) {
        FiltersDTO f = new FiltersDTO();
        f.setDepartment(str(filters.get("department")));
        f.setPattern(str(filters.get("pattern")));
        if (filters.get("dateRange") instanceof List<?> range && range.size() == 2
                && str(range.get(0)) != null && str(range.get(1)) != null) {
            f.setDateRange(List.of(str(range.get(0)), str(range.get(1))));
        }
        return f;
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 从structured_data(附录A结构)按key抽取词频计数
     * Entity数组取content；Herb数组取name；fallback到实体原始字段（兼容未结构化病历）
     */
    private Map<String, Integer> agg(List<Record> records, String jsonKey, String fallbackField) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Record r : records) {
            List<String> terms = extractFromStructured(r, jsonKey);
            if ((terms == null || terms.isEmpty()) && "pattern".equals(fallbackField) && r.getPattern() != null) {
                // 原始辨证结论是多证候组合串（顿号/逗号分隔），切分防整串污染统计
                terms = Arrays.stream(r.getPattern().split("[、，,；;]"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
            // formula无fallback：formulaList为空说明方剂未推断成功，处方串不是方剂名，不计数
            if (terms == null) continue;
            for (String t : terms) {
                if (t != null && !t.isBlank()) counter.merge(t.trim(), 1, Integer::sum);
            }
        }
        return counter;
    }

    /** 解析附录A结构：Entity数组取content，Herb数组取name；无数据返回null（触发fallback） */
    private List<String> extractFromStructured(Record r, String key) {
        if (r.getStructuredData() == null || r.getStructuredData().isBlank()) return null;
        try {
            Map<String, Object> data = objectMapper.readValue(r.getStructuredData(),
                    new TypeReference<Map<String, Object>>() {
                    });
            Object val = data.get(key);
            if (val instanceof List<?> list) {
                List<String> result = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        // Entity: {content, sourceText}；Herb: {name, dosage, sourceText}
                        Object c = m.get("content") != null ? m.get("content") : m.get("name");
                        if (c != null && !String.valueOf(c).isBlank()) result.add(String.valueOf(c));
                    } else if (item != null) {
                        result.add(String.valueOf(item)); // 兼容旧字符串格式
                    }
                }
                return result;
            }
            if (val instanceof String s) {
                return List.of(s);
            }
        } catch (JacksonException ignored) {
        }
        return null;
    }

    private List<Map<String, Object>> top(Map<String, Integer> counter, int limit, String keyName) {
        return counter.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put(keyName, e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();
    }

    private long num(Object o) {
        return o == null ? 0 : Long.parseLong(String.valueOf(o));
    }
}
