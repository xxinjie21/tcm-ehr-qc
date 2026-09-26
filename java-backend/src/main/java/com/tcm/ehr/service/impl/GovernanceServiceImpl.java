package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CleanResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.IGovernanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据清洗服务实现：术语归一、数据清洗、标准数据集导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceServiceImpl extends ServiceImpl<RecordMapper, Record> implements IGovernanceService {

    private final EsTermNormalizer termNormalizer;
    private final ObjectMapper objectMapper;
    private final IDictionaryFileService dictionaryFileService;

    @Override
    public EsTermNormalizer.NormalizeResult normalize(String type, String term) {
        if (!com.tcm.ehr.common.config.EntityTypes.dictKeys().contains(type)) {
            throw new IllegalArgumentException("type必须为disease/pattern/symptom/herb/formula");
        }
        return termNormalizer.normalize(type, term);
    }

    /**
     * 数据清洗5步流水线（项目设计文档9.5）——绝不填充医生未书写的内容：
     * ① 去重 —— 原始文本哈希重复仅保留一条，其余标记无效（不删除）
     * ② 字段清理 —— 仅trim/空值置null（统一格式，绝不填值；缺失由质控扣分、人工复核补充）
     * ③ 格式规整 —— structuredData内herbs剂量单位统一小写表示（只统一写法不改数值）；日期在导出/展示层统一
     * ④ 脏数据隔离 —— 仅"无法修复"的数据（核心文本全空/结构化数据无法解析）标记invalid，格式小问题交质控评分
     * ⑤ 术语归一（兜底） —— 解析环节已首次归一，此处按最新词典对合格病历全库实体补归一
     */
    @Override
    public CleanResultVO clean(List<String> recordIds, com.tcm.ehr.domain.dto.FiltersDTO filters) {
        List<Record> records;
        if (recordIds != null && !recordIds.isEmpty()) {
            records = baseMapper.selectBatchIds(recordIds);
        } else {
            records = baseMapper.selectList(
                    com.tcm.ehr.common.utils.RecordFilter.build(
                            com.tcm.ehr.common.utils.RequestUtils.currentRole(), filters));
        }

        CleanResultVO vo = new CleanResultVO();
        vo.setTotal(records.size());

        Set<String> seenTextHash = new HashSet<>();
        for (Record r : records) {
            String status = r.getStatus();
            String grade = r.getGrade();

            // 已隔离（invalid）的记录跳过：不再重复参与去重/清理统计
            if ("invalid".equals(status)) {
                continue;
            }

            // ① 去重：原始文本哈希（21字段拼接）
            String textHash = RecordUtil.textHash(r);
            if (!seenTextHash.add(textHash)) {
                baseMapper.updateCleanFields(r.getId(), trim(r.getGender()), trim(r.getAge()),
                        trim(r.getPattern()), trim(r.getPrescription()), "invalid", "无效");
                vo.setDeduped(vo.getDeduped() + 1);
                continue;
            }

            // ② 字段清理（trim + 空白置null，不填充任何内容）
            String gender = trim(r.getGender());
            String age = trim(r.getAge());
            String pattern = trim(r.getPattern());
            String prescription = trim(r.getPrescription());
            int repaired = 0;
            if (changed(r.getGender(), gender)) repaired++;
            if (changed(r.getAge(), age)) repaired++;
            if (changed(r.getPattern(), pattern)) repaired++;
            if (changed(r.getPrescription(), prescription)) repaired++;
            vo.setRepaired(vo.getRepaired() + repaired);
            // 空值规整：非空但仅由空白字符构成（trim 后为空）的字段，计为一次"空值规整"
            vo.setCleared(vo.getCleared()
                    + (int) Arrays.asList(r.getGender(), r.getAge(), r.getPattern(), r.getPrescription())
                    .stream().filter(v -> v != null && !v.isEmpty() && v.trim().isEmpty()).count());

            // ④ 脏数据隔离（收紧：仅"无法修复"）——核心文本全空 或 structuredData存在但无法解析
            boolean unrecoverable = (isBlank(r.getChiefComplaint()) && isBlank(r.getTcmDiagnosis())
                    && isBlank(r.getPresentIllness()) && isBlank(r.getSelfReport()))
                    || (r.getStructuredData() != null && !r.getStructuredData().isBlank()
                        && !isValidJson(r.getStructuredData()));
            if (unrecoverable && !"invalid".equals(status)) {
                vo.setIsolated(vo.getIsolated() + 1);
                status = "invalid";
                grade = "无效";
            }

            baseMapper.updateCleanFields(r.getId(), gender, age, pattern, prescription, status, grade);

            // ⑤ 术语归一（兜底）：仅对合格病历执行，归一后标记已清洗
            if ("合格".equals(grade) && r.getStructuredData() != null && !r.getStructuredData().isBlank()) {
                int[] norm = normalizeStructuredData(r);
                vo.setNormalized(vo.getNormalized() + norm[0]);
                vo.getNormByLevel().setExact(vo.getNormByLevel().getExact() + norm[1]);
                vo.getNormByLevel().setContain(vo.getNormByLevel().getContain() + norm[2]);
                vo.getNormByLevel().setFuzzy(vo.getNormByLevel().getFuzzy() + norm[3]);
                baseMapper.markGoverned(r.getId());
            }
        }
        log.info("[清洗] 数据清洗完成: total={}, deduped={}, repaired={}, isolated={}, normalized={}",
                vo.getTotal(), vo.getDeduped(), vo.getRepaired(), vo.getIsolated(), vo.getNormalized());
        return vo;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isValidJson(String s) {
        try {
            objectMapper.readTree(s);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

    /**
     * 对structuredData（附录A结构）全实体做术语归一：content替换为标准词，sourceText保留原文；
     * 命中实体写入 normLevel(1/2/3) 与 normSource（批B·2.2），供前端溯源与三级分布统计。
     *
     * @return {被替换实体数, 精确数, 包含数, 模糊数}
     */
    private int[] normalizeStructuredData(Record r) {
        int[] stat = {0, 0, 0, 0};
        try {
            Map<String, Object> data = objectMapper.readValue(r.getStructuredData(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            // Entity数组：content归一（diseases/symptoms/patternList/formulaList有对应词典）
            for (String key : List.of("diseases", "symptoms", "tongueList", "pulseList", "patternList",
                    "causeList", "treatmentList", "formulaList")) {
                if (!(data.get(key) instanceof List<?> list)) continue;
                String type = mapEntityType(key);
                // 无词典的 4 类（舌/脉/病因/治法）不做归一，但仍要走下面的同标准词去重
                if (type != null) {
                    for (Object item : list) {
                        if (!(item instanceof Map)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entity = (Map<String, Object>) item;
                        Object content = entity.get("content");
                        if (content == null || String.valueOf(content).isBlank()) continue;
                        var result = termNormalizer.normalize(type, String.valueOf(content));
                        if (result.source() != null && !result.source().isBlank()
                                && !result.standardTerm().equals(String.valueOf(content))) {
                            entity.put("content", result.standardTerm());
                            entity.put("normLevel", result.level());
                            entity.put("normSource", result.source());
                            if (result.code() != null) {
                                entity.put("normCode", result.code());
                            }
                            stat[0]++;
                            if (result.level() >= 1 && result.level() <= 3) stat[result.level()]++;
                        }
                    }
                }
                // 同标准词去重（第八轮）：口径与解析链路共用 EntityNormalizer.dedupByTerm
                data.put(key, dedupStructuredList(list, "content"));
            }
            // herbs：name归一 + dosage格式规整（统一小写单位表示，不改数值）
            if (data.get("herbs") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> herb = (Map<String, Object>) item;
                    Object name = herb.get("name");
                    if (name == null || String.valueOf(name).isBlank()) continue;
                    var result = termNormalizer.normalize("herb", String.valueOf(name));
                    if (result.source() != null && !result.source().isBlank()
                            && !result.standardTerm().equals(String.valueOf(name))) {
                        herb.put("name", result.standardTerm());
                        herb.put("normLevel", result.level());
                        herb.put("normSource", result.source());
                        if (result.code() != null) {
                            herb.put("normCode", result.code());
                        }
                        stat[0]++;
                        if (result.level() >= 1 && result.level() <= 3) stat[result.level()]++;
                    }
                    if (herb.get("dosage") != null) {
                        String d = String.valueOf(herb.get("dosage")).trim().toLowerCase();
                        if (!d.equals(String.valueOf(herb.get("dosage")))) herb.put("dosage", d);
                    }
                }
                data.put("herbs", dedupStructuredList(list, "name"));
            }
            String json = StructuredDataMeta.stamp(objectMapper, objectMapper.writeValueAsString(data),
                    dictionaryFileService.currentVersion());
            baseMapper.updateStructuredData(r.getId(), json);
        } catch (JacksonException e) {
            log.warn("[清洗] structuredData归一失败 recordId={}: {}", r.getId(), e.getMessage());
        }
        return stat;
    }

    /**
     * structuredData 里某一类实体的「同标准词去重」（第八轮）。
     *
     * <p><b>不另写一套规则</b>：直接复用解析链路用的 {@link EntityNormalizer#dedupByTerm}，
     * 与 {@code mapEntityType} 同一思路——口径只写一处，避免解析链路与清洗链路漂移。</p>
     *
     * <p>键取 {@code termField}（实体为 {@code content}、中药为 {@code name}）；键为空的元素
     * 由 dedupByTerm 用唯一键占位，不参与合并。归一后 content 相同者只留一条，
     * 代表条目的选取规则见 dedupByTerm 的注释。</p>
     */
    @SuppressWarnings("unchecked")
    private List<Object> dedupStructuredList(List<?> list, String termField) {
        if (list == null || list.size() < 2) {
            return (List<Object>) list;
        }
        return EntityNormalizer.dedupByTerm((List<Object>) list,
                item -> mapStr(item, termField),
                item -> mapStr(item, "sourceText"),
                GovernanceServiceImpl::mapLevel);
    }

    /** 取 structuredData 实体里的字符串字段；非 Map 或字段缺失返回 null */
    private static String mapStr(Object item, String field) {
        if (!(item instanceof Map<?, ?> m)) return null;
        Object v = m.get(field);
        return v == null ? null : String.valueOf(v);
    }

    /** 取 normLevel；非数字返回 null（未归一 / 未命中词典） */
    private static Integer mapLevel(Object item) {
        if (!(item instanceof Map<?, ?> m)) return null;
        Object v = m.get("normLevel");
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /** 附录A 字段名 → 词典类型；映射唯一权威在 {@link EntityNormalizer#dictionaryType}（解析链路共用） */
    private String mapEntityType(String key) {
        return EntityNormalizer.dictionaryType(key);
    }

    /**
     * 标准数据集导出：仅质控合格病历 + 敏感信息脱敏（手机号/身份证号→***）
     * filters复用基础查询条件（查询1字段）：department、dateRange、pattern
     * 无合格数据返回null（Controller返回2001）
     */
    @Override
    public ExportedFile export(ExportDTO dto) throws IOException {
        List<Record> records = filterQualified(dto);
        if (records.isEmpty()) {
            return null;
        }

        if ("json".equalsIgnoreCase(dto.getFormat())) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            return new ExportedFile("tcm_ehr_dataset_" + ts() + ".json",
                    mask(json).getBytes(StandardCharsets.UTF_8));
        }
        return new ExportedFile("tcm_ehr_dataset_" + ts() + ".csv", maskCsv(toCsv(records)));
    }

    @Override
    public Map<String, Object> previewDataset(ExportDTO dto) {
        List<Record> records = filterQualified(dto);
        Map<String, Object> result = new HashMap<>();
        result.put("total", records.size());
        List<Record> sample = records.subList(0, Math.min(10, records.size()));
        // 预览与导出必须走同一套脱敏。此前预览直接把实体塞进响应 ——
        // 于是同一条现病史（可能写着手机号）在导出件里打码、在预览表格里明文。
        result.put("sample", maskedSample(sample));
        return result;
    }

    /**
     * 样本记录按导出同一口径脱敏：先序列化，再用 {@link #mask} 打码，最后解析回结构。
     *
     * <p>键名与类型不变，前端预览表格（按 prop 取值）无需改动；visitTime 仍是字符串，
     * 所以列上的 {@code substring(0,10)} 照常可用。</p>
     *
     * <p>刻意<b>不</b>捕获异常：脱敏失败就应当让这一次预览失败（与导出路径一致），
     * 而不是静默返回未脱敏的样本 —— 后者是把「承诺打码却出了明文」当成正常结果。</p>
     */
    private Object maskedSample(List<Record> sample) {
        String json = objectMapper.writeValueAsString(sample);
        return objectMapper.readValue(mask(json), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    @Override
    public Map<String, Object> governanceStats() {
        return baseMapper.selectGovernanceStats();
    }

    /** 导出过滤：质控合格 + filters复用查询1条件（department/dateRange/pattern） */
    private List<Record> filterQualified(ExportDTO dto) {
        Map<String, Object> filters = dto.getFilters() == null ? Map.of() : dto.getFilters();
        String department = str(filters.get("department"));
        String pattern = str(filters.get("pattern"));
        String start = null;
        String end = null;
        if (filters.get("dateRange") instanceof List<?> range && range.size() == 2) {
            start = str(range.get(0));
            end = str(range.get(1));
        }

        final String dep = department;
        final String pat = pattern;
        final String dateStart = start;
        final String dateEnd = end;

        return baseMapper.selectList(null).stream()
                // 仅质控合格（分级路由：合格→导出；待复核/无效禁止进入数据集）
                .filter(r -> "合格".equals(r.getGrade()))
                .filter(r -> dep == null || dep.equals(r.getDepartment()))
                .filter(r -> {
                    if (dateStart == null && dateEnd == null) return true;
                    String d = r.getVisitTime() == null ? "" : r.getVisitTime().toString().substring(0, 10);
                    return (dateStart == null || (d.compareTo(dateStart) >= 0))
                            && (dateEnd == null || (d.compareTo(dateEnd) <= 0));
                })
                .filter(r -> pat == null || structuredPatternContains(r, pat))
                .toList();
    }

    /** structuredData.patternList 是否包含指定证候（模糊包含匹配） */
    private boolean structuredPatternContains(Record r, String pattern) {
        if (r.getStructuredData() == null) return false;
        try {
            Map<String, Object> data = objectMapper.readValue(r.getStructuredData(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            if (data.get("patternList") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object c = m.get("content");
                        if (c != null && String.valueOf(c).contains(pattern)) return true;
                    }
                }
            }
            return r.getPattern() != null && r.getPattern().contains(pattern);
        } catch (JacksonException e) {
            return false;
        }
    }

    /** 敏感信息脱敏：11位手机号、18位身份证号 → *** */
    private static final String PHONE_RE = "(?<!\\d)1[3-9]\\d{9}(?!\\d)";
    private static final String ID_RE = "(?<!\\d)\\d{17}[\\dXx](?!\\d)";

    private String mask(String s) {
        return s.replaceAll(PHONE_RE, "***").replaceAll(ID_RE, "***");
    }

    private byte[] maskCsv(byte[] csv) {
        return mask(new String(csv, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private byte[] toCsv(List<Record> records) throws IOException {
        String[] headers = {"id", "挂号号", "门诊号", "性别", "年龄", "就诊次数", "西医诊断", "中医诊断",
                "现病史", "主诉", "自述", "望诊", "脉象", "舌象", "体格检查", "辨证结论", "处方",
                "随访", "治疗效果", "科室", "医生ID", "就诊时间"};
        List<String> cols = List.of("id", "registrationNo", "outpatientNo", "gender", "age", "visitCount",
                "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint",
                "selfReport", "inspection", "pulse", "tongue", "physicalExam", "pattern",
                "prescription", "followUp", "treatmentEffect", "department", "doctorId", "visitTime");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM
        out.write(String.join(",", headers).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        for (Record r : records) {
            List<String> cells = new ArrayList<>();
            for (String col : cols) {
                Object v = objectMapper.convertValue(r, Map.class).get(col);
                // visitTime格式规整：展示/导出统一为YYYY-MM-DD（文档9.5③格式规整）
                if ("visitTime".equals(col) && v != null) {
                    v = String.valueOf(v).substring(0, 10);
                }
                String s = v == null ? "" : String.valueOf(v).replace("\"", "\"\"").replace("\n", " ");
                cells.add("\"" + s + "\"");
            }
            out.write(String.join(",", cells).getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        return out.toByteArray();
    }

    private String trim(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    private boolean changed(String oldV, String newV) {
        return oldV != null && !oldV.equals(newV);
    }

    private String ts() {
        return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
