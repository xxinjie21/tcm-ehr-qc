package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CleanResultVO;
import com.tcm.ehr.mapper.RecordMapper;
import java.time.LocalDateTime;
import com.tcm.ehr.service.IGovernanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

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

    /** 预览只回前 10 条 */
    private static final int PREVIEW_SAMPLE_SIZE = 10;

    private final EsTermNormalizer termNormalizer;
    private final ObjectMapper objectMapper;
    private final com.tcm.ehr.service.DictionaryTermStore termStore;
    /** 隔离病历时要同步作废它的待复核任务，否则它会继续挂在复核页待办里 */
    private final com.tcm.ehr.mapper.ReviewTaskMapper reviewTaskMapper;

    /**
     * 单条术语归一（清洗页的「归一测试」入口）。
     *
     * <p>只接受有独立词典的 5 类（疾病 / 证候 / 症状 / 中药 / 方剂），其余类型直接拒绝，
     * 避免调用方以为舌象、脉象也有词典可查。</p>
     *
     * @param type 实体类型 key
     * @param term 待归一原文
     * @return 命中层级、标准词、来源与国标代码
     * @throws IllegalArgumentException type 不属于 5 类词典类型
     */
    @Override
    public EsTermNormalizer.NormalizeResult normalize(String type, String term) {
        // 1. 只收词典里的 5 类：舌象/脉象等无词典，归一了也没有权威结果可依
        if (!com.tcm.ehr.common.config.EntityTypes.dictKeys().contains(type)) {
            throw new IllegalArgumentException("type必须为disease/pattern/symptom/herb/formula");
        }
        return termNormalizer.normalize(type, RequestUtils.currentOrgId(), term);
    }

    /**
     * 数据清洗五步流水线：去重 → 字段清理 → 空值规整 → 脏数据隔离 → 术语归一。
     *
     * <p>只规整与标记，<b>不填充医生未书写的内容，也不删除任何病历</b>；各步的判断口径见方法体注释。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CleanResultVO clean(List<String> recordIds, com.tcm.ehr.domain.dto.FiltersDTO filters) {
        List<Record> records;
        if (recordIds != null && !recordIds.isEmpty()) {
            // § 6.3 缺点 2：按 ID 批量取不能用了 selectBatchIds 之后
            // 不再校验——“把别组的 id 传进来清洗”会直接成立。
            // 改成先按本组过滤后再取，取不到的忽略（不报错：
            // 一个 id 是否属于本组属于授权问题，不属于业务错误）。
            records = baseMapper.selectList(RecordFilter
                    .build(RequestUtils.currentOrgId(), new FiltersDTO())
                    .in("id", recordIds));
        } else {
            records = baseMapper.selectList(
                    com.tcm.ehr.common.utils.RecordFilter.build(
                            RequestUtils.currentOrgId(), filters));
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

            // 1. 去重：原始文本哈希（21字段拼接）
            String textHash = RecordUtil.textHash(r);
            if (!seenTextHash.add(textHash)) {
                baseMapper.updateCleanFields(r.getId(), trim(r.getGender()), trim(r.getAge()),
                        trim(r.getPattern()), trim(r.getPrescription()), "invalid", "无效");
                // 与下面「不可修复」分支同理：隔离必须同步作废待复核任务
                reviewTaskMapper.obsoleteActive(r.getId(), LocalDateTime.now().withNano(0));
                vo.setDeduped(vo.getDeduped() + 1);
                continue;
            }

            // 2. 字段清理（trim + 空白置null，不填充任何内容）
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
            // 3. 空值规整：非空但仅由空白字符构成（trim 后为空）的字段，计为一次"空值规整"
            vo.setCleared(vo.getCleared()
                    + (int) Arrays.asList(r.getGender(), r.getAge(), r.getPattern(), r.getPrescription())
                    .stream().filter(v -> v != null && !v.isEmpty() && v.trim().isEmpty()).count());

            // 4. 脏数据隔离（收紧：仅"无法修复"）——核心文本全空 或 structuredData存在但无法解析
            boolean unrecoverable = (TextUtil.isBlank(r.getChiefComplaint()) && TextUtil.isBlank(r.getTcmDiagnosis())
                    && TextUtil.isBlank(r.getPresentIllness()) && TextUtil.isBlank(r.getSelfReport()))
                    || (r.getStructuredData() != null && !r.getStructuredData().isBlank()
                        && !isValidJson(r.getStructuredData()));
            boolean isolatedNow = unrecoverable && !"invalid".equals(status);
            if (isolatedNow) {
                vo.setIsolated(vo.getIsolated() + 1);
                status = "invalid";
                grade = "无效";
                // 隔离必须同步作废待复核任务：不作废的话它仍挂在复核页，
                // 而复核会按当前数据重算，把「无效」翻回「合格」——隔离结论就被撤销了
                reviewTaskMapper.obsoleteActive(r.getId(), LocalDateTime.now().withNano(0));
            }

            if (isolatedNow) {
                // 只有真改了结论才写 status/grade；常规路径不碰这两列，
                // 否则会把清洗开始时的旧快照盖到并发质控/复核的新结论上
                baseMapper.updateCleanFields(r.getId(), gender, age, pattern, prescription, status, grade);
            } else {
                baseMapper.updateCleanFieldsWithoutStatus(r.getId(), gender, age, pattern, prescription);
            }

            // 6. 回写 text_hash：上面 trim/置空的四列都参与哈希，不回写会让「库里存的哈希」
            //    与「按内容现算的哈希」永久不一致 —— 导入的判重预筛（按内容现算）就再也
            //    认不出这条病历，同一份数据会重新进库
            r.setGender(gender);
            r.setAge(age);
            r.setPattern(pattern);
            r.setPrescription(prescription);
            String freshHash = RecordUtil.textHash(r);
            if (!freshHash.equals(r.getTextHash())) {
                try {
                    baseMapper.updateTextHash(r.getId(), freshHash);
                } catch (DataIntegrityViolationException dup) {
                    // 撞唯一键 = 该机构内已有一条内容相同的病历，与本方法开头的去重是同一结论
                    log.warn("[清洗] 病历 {} 回写 text_hash 撞唯一键，按重复处理并置为无效", r.getId());
                    baseMapper.updateCleanFields(r.getId(), gender, age, pattern, prescription, "invalid", "无效");
                    reviewTaskMapper.obsoleteActive(r.getId(), LocalDateTime.now().withNano(0));
                    vo.setDeduped(vo.getDeduped() + 1);
                    continue;
                }
            }

            // 5. 术语归一（兜底）：仅对合格病历执行，归一后标记已清洗
            //    ⚠️ 人工修改过的病历**跳过归一**（方案 A）：归一会重跑标准化，把人工改成
            //    非标准词的术语又归一回标准词 —— 等于清洗一次就撤销一次人工修正。
            //    人工成果优先，所以这里直接不碰它的 structured_data。
            //    注意：合格但 structured_data 为空/空白的病历不在此列，它保持「待清洗」，
            //    因而不会被导出条件 governed=1 选中 —— 这是刻意的（没有结构化结果不算标准数据集）。
            if ("合格".equals(grade) && r.getStructuredData() != null && !r.getStructuredData().isBlank()) {
                if (StructuredDataMeta.isManuallyEdited(objectMapper, r.getStructuredData())) {
                    vo.setManualSkipped(vo.getManualSkipped() + 1);
                    // 人工修正过的按已清洗处理：它的 structured_data 已定案（方案 A 不重跑归一），
                    // 不打标记会让它永远落在「待清洗」，并被导出条件 governed=1 永久排除
                    baseMapper.markGoverned(r.getId());
                } else {
                    int[] norm = normalizeStructuredData(r);
                    vo.setNormalized(vo.getNormalized() + norm[0]);
                    vo.getNormByLevel().setExact(vo.getNormByLevel().getExact() + norm[1]);
                    vo.getNormByLevel().setContain(vo.getNormByLevel().getContain() + norm[2]);
                    vo.getNormByLevel().setFuzzy(vo.getNormByLevel().getFuzzy() + norm[3]);
                    baseMapper.markGoverned(r.getId());
                }
            }
        }
        log.info("[清洗] 数据清洗完成: total={}, deduped={}, repaired={}, isolated={}, normalized={}",
                vo.getTotal(), vo.getDeduped(), vo.getRepaired(), vo.getIsolated(), vo.getNormalized());
        return vo;
    }

    /** 判断是否为可解析的 JSON（隔离脏数据用；不可解析即视为"无法修复"） */
    private boolean isValidJson(String s) {
        // 1. 能解析即有效 2. 解析不了 = 无法修复的脏数据
        try {
            objectMapper.readTree(s);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

    /**
     * 对结构化数据的全实体补做术语归一：content 换成标准词、sourceText 保留原文，
     * 并写入 normLevel(1/2/3) 与 normSource 供前端溯源与三级分布统计。
     *
     * @return 依次为 被替换实体数、精确数、包含数、模糊数
     */
    private int[] normalizeStructuredData(Record r) {
        int[] stat = {0, 0, 0, 0};
        try {
            Map<String, Object> data = objectMapper.readValue(r.getStructuredData(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            // 1. 8 类 entity 逐类归一 + 同标准词去重（P3.4 拆出）
            normalizeEntityList(data, stat);
            // 2. 中药走另一套：name 归一 + 剂量单位小写（P3.4 拆出）
            normalizeHerbs(data, stat);
            // 3. 打上词典版本再写库：归一结果与当时词典版本必须成对
            //    ⚠️ 版本源换成 DictionaryTermStore：原先用
            //    dictionaryFileService.currentVersion()（词典还在文件时代的文件哈希），
            //    批次 8b 词典入库后它已冻结 —— 清洗一次就把正确的版本戳覆盖回那个死值。
            String json = StructuredDataMeta.stamp(objectMapper, objectMapper.writeValueAsString(data),
                    termStore.effectiveDictVersion(RequestUtils.currentOrgId()),
                    termStore.effectiveTermCount(RequestUtils.currentOrgId()));
            baseMapper.updateStructuredData(r.getId(), json);
        } catch (JacksonException e) {
            // 单条解析失败只记警告：一条脏数据不该中断整批清洗
            log.warn("[清洗] structuredData归一失败 recordId={}: {}", r.getId(), e.getMessage());
        }
        return stat;
    }

    /** 8 类 entity（content）逐类归一并做同标准词去重；stat 就地累加 */
    @SuppressWarnings("unchecked")
    private void normalizeEntityList(Map<String, Object> data, int[] stat) {
        for (String key : List.of("diseases", "symptoms", "tongueList", "pulseList", "patternList",
                "causeList", "treatmentList", "formulaList")) {
            if (!(data.get(key) instanceof List<?> list)) continue;
            String type = mapEntityType(key);
            // 有词典的才做词形归一；舌/脉/病因/治法这 4 类无词典，但仍要走去重
            if (type != null) {
                for (Object item : list) {
                    if (!(item instanceof Map)) continue;
                    Map<String, Object> entity = (Map<String, Object>) item;
                    Object content = entity.get("content");
                    if (content == null || String.valueOf(content).isBlank()) continue;
                    var result = termNormalizer.normalize(type, RequestUtils.currentOrgId(), String.valueOf(content));
                    // 只在「命中词典且词形确实变了」时才改写并记统计，
                    // 否则会把未命中的实体也标成已归一
                    if (result.source() != null && !result.source().isBlank()
                            && !result.standardTerm().equals(String.valueOf(content))) {
                        entity.put("content", result.standardTerm());
                        entity.put("normLevel", result.level());
                        entity.put("normSource", result.source());
                        if (result.code() != null) {
                            entity.put("normCode", result.code());
                        }
                        stat[0]++;
                        if (result.level() >= EsTermNormalizer.LEVEL_EXACT
                && result.level() <= EsTermNormalizer.LEVEL_FUZZY) stat[result.level()]++;
                    }
                }
            }
            // 同标准词去重：口径与解析链路共用 EntityNormalizer.dedupByTerm
            data.put(key, dedupStructuredList(list, "content"));
        }
    }

    /** 中药（name）归一 + 剂量单位小写（数值不动，改数值会失真）+ 同药名去重；stat 就地累加 */
    @SuppressWarnings("unchecked")
    private void normalizeHerbs(Map<String, Object> data, int[] stat) {
        if (!(data.get("herbs") instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> herb = (Map<String, Object>) item;
            Object name = herb.get("name");
            if (name == null || String.valueOf(name).isBlank()) continue;
            var result = termNormalizer.normalize("herb", RequestUtils.currentOrgId(), String.valueOf(name));
            if (result.source() != null && !result.source().isBlank()
                    && !result.standardTerm().equals(String.valueOf(name))) {
                herb.put("name", result.standardTerm());
                herb.put("normLevel", result.level());
                herb.put("normSource", result.source());
                if (result.code() != null) {
                    herb.put("normCode", result.code());
                }
                stat[0]++;
                if (result.level() >= EsTermNormalizer.LEVEL_EXACT
                && result.level() <= EsTermNormalizer.LEVEL_FUZZY) stat[result.level()]++;
            }
            if (herb.get("dosage") != null) {
                String d = String.valueOf(herb.get("dosage")).trim().toLowerCase();
                if (!d.equals(String.valueOf(herb.get("dosage")))) herb.put("dosage", d);
            }
        }
        data.put("herbs", dedupStructuredList(list, "name"));
    }

    /**
     * 某一类实体的"同标准词去重"，直接复用解析链路的 {@link EntityNormalizer#dedupByTerm}。
     *
     * <p>合并键取 {@code termField}（实体 content、中药 name）；代表条目的选取规则见该方法注释。</p>
     */
    @SuppressWarnings("unchecked")
    private List<Object> dedupStructuredList(List<?> list, String termField) {
        // 1. 少于两条无从去重，原样返回
        if (list == null || list.size() < 2) {
            return (List<Object>) list;
        }
        // 2. 合并键与代表条目选取规则都复用解析链路，避免两处口径漂移
        return EntityNormalizer.dedupByTerm((List<Object>) list,
                item -> mapStr(item, termField),
                item -> mapStr(item, "sourceText"),
                GovernanceServiceImpl::mapLevel);
    }

    /** 取 structuredData 实体里的字符串字段；非 Map 或字段缺失返回 null */
    private static String mapStr(Object item, String field) {
        // 1. 非 Map（脏数据）返回 null 2. 取字段值并转字符串
        if (!(item instanceof Map<?, ?> m)) return null;
        Object v = m.get(field);
        return v == null ? null : String.valueOf(v);
    }

    /** 取 normLevel；非数字返回 null（未归一 / 未命中词典） */
    private static Integer mapLevel(Object item) {
        // 非数字（未归一/脏数据）返回 null，让去重时按"层级未知"处理
        if (!(item instanceof Map<?, ?> m)) return null;
        Object v = m.get("normLevel");
        return (v instanceof Number n) ? n.intValue() : null;
    }

    /** 附录A 字段名 → 词典类型；映射唯一权威在 {@link EntityNormalizer#dictionaryType}（解析链路共用） */
    private String mapEntityType(String key) {
        return EntityNormalizer.dictionaryType(key);
    }

    /**
     * 标准数据集导出：只含质控合格病历，并对手机号/身份证号脱敏。
     *
     * <p>范围内无合格病历时返回 {@code null}，由 Controller 转 400 + code=2001。</p>
     */
    @Override
    public ExportedFile export(ExportDTO dto) throws IOException {
        // 1. 只导合格病历；一条都没有就返回 null，由 Controller 转 400
        List<Record> records = filterQualified(dto);
        if (records.isEmpty()) {
            return null;
        }

        // 2. JSON：序列化后整体打码
        if ("json".equalsIgnoreCase(dto.getFormat())) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            return new ExportedFile("tcm_ehr_dataset_" + ts() + ".json",
                    mask(json).getBytes(StandardCharsets.UTF_8));
        }
        // 3. CSV：先生成再对字节打码
        return new ExportedFile("tcm_ehr_dataset_" + ts() + ".csv", maskCsv(toCsv(records)));
    }

    /**
     * 导出预览：返回总条数 + 前 10 条样本。
     *
     * <p>不带证候筛选时走 COUNT 与 LIMIT 两条轻查询，不把全表读进内存；
     * 带证候筛选时证候在 JSON 里 SQL 表达不了，退化为"SQL 收窄后内存筛 + 取前 10"。</p>
     */
    @Override
    public Map<String, Object> previewDataset(ExportDTO dto) {
        Map<String, Object> result = new HashMap<>();
        List<Record> sample;
        // 1. 无证候筛选：走 SQL（COUNT 与 LIMIT 两条轻查询，不把全表读进内存）
        if (patternOf(dto) == null) {
            result.put("total", baseMapper.selectCount(qualifiedWrapper(dto)));
            // LIMIT 只加在这里：导出要全量，预览只要 10 条
            sample = baseMapper.selectList(qualifiedWrapper(dto).last("LIMIT " + PREVIEW_SAMPLE_SIZE));
        } else {
            // 2. 带证候筛选：证候在 JSON 里 SQL 筛不了，退化为内存筛后取前 10
            List<Record> records = filterQualified(dto);
            result.put("total", (long) records.size());
            sample = records.subList(0, Math.min(PREVIEW_SAMPLE_SIZE, records.size()));
        }
        // 3. 预览样本与导出件走同一套脱敏。此前预览直接塞实体 ——
        // 于是同一条现病史（可能写着手机号）在导出件里打码、在预览表格里明文。
        result.put("sample", maskedSample(sample));
        return result;
    }

    /**
     * 样本按导出同一口径脱敏：先序列化、再打码、最后解析回结构，键名与类型不变。
     *
     * <p>刻意不捕获异常：脱敏失败就让这次预览失败，而不是静默返回未脱敏的样本。</p>
     */
    private Object maskedSample(List<Record> sample) {
        String json = objectMapper.writeValueAsString(sample);
        return objectMapper.readValue(mask(json), new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * 清洗与导出页顶部的统计卡（待清洗 / 已清洗 / 可导出等）。
     *
     * @return 由 {@code RecordMapper.selectGovernanceStats()} 单条聚合 SQL 出的计数
     */
    @Override
    public Map<String, Object> governanceStats() {
        // viewAllOrgs 一并下推：清洗页的「待清洗/已清洗」必须与 clean() 实际会处理的范围同域
        return baseMapper.selectGovernanceStats(RecordFilter.domainOrgId(), RequestUtils.viewAllOrgs());
    }

    /**
     * 导出/预览共用的范围条件：能下推 SQL 的都下推，再追加"只含合格"这条硬约束。
     *
     * <p>证候存在 {@code structured_data} 的 JSON 里，SQL 表达不了，只能留给调用方在内存筛。</p>
     */
    private QueryWrapper<Record> qualifiedWrapper(ExportDTO dto) {
        // 1. 条件组装复用 RecordFilter（与其余读路径同一个函数），但**去掉 pattern**：
        //    证候存在 structured_data 的 JSON 里，SQL 表达不了；若在这里带上，
        //    RecordFilter 会把它翻译成「原始 pattern 列 LIKE」，而归一后的证候写法
        //    常与医生原写法不同 —— 那一步收窄会在内存筛之前就把本该命中的病历排除掉。
        //    证候判定统一交给 filterQualified 的内存筛（含原始列回退）。
        FiltersDTO f = RecordFilter.fromMap(dto.getFilters());
        if (f != null) {
            f.setPattern(null);
        }
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(), f);
        // 2. 追加导出自己的硬约束：只导「合格且已清洗」的病历。
        //    governed=1 是清洗完成的标记（术语归一跑过、且不是人工跳过的那一类），
        //    少了它，从未归一过的结构化数据会以「标准数据集」的名义被导出去
        wrapper.eq("grade", "合格");
        wrapper.eq("governed", 1);
        return wrapper;
    }

    private String patternOf(ExportDTO dto) {
        Map<String, Object> filters = dto.getFilters() == null ? Map.of() : dto.getFilters();
        return toTrimmedOrNull(filters.get("pattern"));
    }

    /** 取范围内合格病历；带证候筛选时额外做内存筛（证候在 JSON 里，SQL 筛不了） */
    private List<Record> filterQualified(ExportDTO dto) {
        // 1. 先用 SQL 收窄（数据域 + 用户筛选 + 只含合格）
        List<Record> records = baseMapper.selectList(qualifiedWrapper(dto));
        // 2. 证候筛选在 JSON 里，只能内存筛；没这项就直接返回
        String pattern = patternOf(dto);
        return pattern == null
                ? records
                : records.stream().filter(r -> structuredPatternContains(r, pattern)).toList();
    }

    /** structuredData.patternList 是否包含指定证候（模糊包含匹配），否则回退原始辨证结论列 */
    private boolean structuredPatternContains(Record r, String pattern) {
        // 1. 只在真有结构化数据时才解析 JSON。
        //    不能在这里提前 return false —— 注释里承诺的「原始列回退」会永远走不到，
        //    未结构化的合格病历会在带证候筛选时被整批丢掉
        if (r.getStructuredData() == null || r.getStructuredData().isBlank()) {
            return r.getPattern() != null && r.getPattern().contains(pattern);
        }
        try {
            Map<String, Object> data = objectMapper.readValue(r.getStructuredData(),
                    new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            // 2. 先看归一后的证候列表（这是质控实际认的证候）
            if (data.get("patternList") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Object c = m.get("content");
                        if (c != null && String.valueOf(c).contains(pattern)) return true;
                    }
                }
            }
            // 3. 再回退原始辨证结论：未结构化的病历只能靠这一列
            return r.getPattern() != null && r.getPattern().contains(pattern);
        } catch (JacksonException e) {
            // 4. JSON 坏了当不匹配，不让一条脏数据把整个导出带崩
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

    /**
     * trim 后为空（null / 空串 / 纯空白）一律归成 {@code null}，否则原样返回。
     *
     * <p>脱敏与清洗要的是「这个字段真的没有值」，不是「它是个空字符串」——
     * 留着空串会让下游把它当成一个有内容的字段继续处理。</p>
     *
     * <p>与 {@code AiServiceImpl.rawOrNull}（不 trim、null 保持 null）语义不同，
     * 与 {@code EsTermIndexServiceImpl.nullToEmpty}（null 变空串）也相反。</p>
     */
    private String toTrimmedOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private byte[] toCsv(List<Record> records) throws IOException {
        // 1. 表头与列顺序一一对应，改一处必须改另一处
        String[] headers = {"id", "挂号号", "门诊号", "性别", "年龄", "就诊次数", "西医诊断", "中医诊断",
                "现病史", "主诉", "自述", "望诊", "脉象", "舌象", "体格检查", "辨证结论", "处方",
                "随访", "治疗效果", "科室", "医生ID", "就诊时间"};
        List<String> cols = List.of("id", "registrationNo", "outpatientNo", "gender", "age", "visitCount",
                "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint",
                "selfReport", "inspection", "pulse", "tongue", "physicalExam", "pattern",
                "prescription", "followUp", "treatmentEffect", "department", "doctorId", "visitTime");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 2. 先写 UTF-8 BOM：Excel 靠它认编码，否则中文列名会乱码
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.write(String.join(",", headers).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        // 3. 逐行输出，所有单元格加引号并转义内部引号/换行
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

    /** 导出文件名用毫秒精度：秒级会让同一秒内的两次导出得到同名文件 */
    private String ts() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
    }
}
