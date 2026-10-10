package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.utils.DictMeta;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordKeyset;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 数据清洗服务实现：术语归一、数据清洗、标准数据集导出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceServiceImpl extends ServiceImpl<RecordMapper, Record> implements IGovernanceService {

    /** 预览只回前 10 条 */
    private static final int PREVIEW_SAMPLE_SIZE = 10;

    /** 导出流式分批大小（性能审查 A7）：边取边写，内存恒定一页，不再全量 selectList */
    private static final int EXPORT_PAGE_SIZE = 1000;

    private final EsTermNormalizer termNormalizer;
    private final ObjectMapper objectMapper;
    private final com.tcm.ehr.service.IDictionaryTermStore termStore;
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
     * @return 命中层级、标准词
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
        // 批次12 · 12c（第二步）：由「整批载入」改为**惰性分页**。
        // 原实现把 3.5 万行一次读进内存，每行还带 1.3KB 的 structured_data（合计 43.8MB）——
        // 这是清洗的堆占用来源。下面保持 for-each 写法**一行不改**，只把数据源换成按页拉取。
        QueryWrapper<Record> wrapper = recordIds != null && !recordIds.isEmpty()
                ? RecordFilter.build(RequestUtils.currentOrgId(), new FiltersDTO()).in("id", recordIds)
                : com.tcm.ehr.common.utils.RecordFilter.build(RequestUtils.currentOrgId(), filters);
        Iterable<Record> records = pagedRecords(wrapper);

        CleanResultVO vo = new CleanResultVO();
        // total 单独查一次：逐页再算总数是浪费（分页时已关闭 searchCount）。
        // 注意 selectCount 返回 Long，不能 (int) 直接强转，须 intValue()。
        vo.setTotal(baseMapper.selectCount(wrapper).intValue());

        Set<String> seenTextHash = new HashSet<>();
        // T13：词典元数据一批只取一次（惰性 —— 本批没有任何归一需求时一次库都不查）。
        // 原先 normalizeStructuredData 逐条调 effectiveDictVersion / effectiveTermCount，
        // 每次又各自查库，4 万条就是约 16 万次 SQL；而 orgId 在整批内恒定，
        // 同一批也不该盖上两个不同的版本戳。
        DictMeta dictMeta = new DictMeta(termStore, RequestUtils.currentOrgId());
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
            //    T16：结构化数据在这里**只解析一次**，解析结果向下传给第 5 步的归一；
            //    不再让「校验（readTree）」与「归一（readValue）」各解析同一份 JSON 一遍。
            //    structured == null 同时覆盖「没有 structured_data」与「有但解析不了」两种情形。
            boolean hasStructured = r.getStructuredData() != null && !r.getStructuredData().isBlank();
            Map<String, Object> structured = hasStructured ? parseStructuredData(r.getStructuredData()) : null;
            boolean unrecoverable = (TextUtil.isBlank(r.getChiefComplaint()) && TextUtil.isBlank(r.getTcmDiagnosis())
                    && TextUtil.isBlank(r.getPresentIllness()) && TextUtil.isBlank(r.getSelfReport()))
                    || (hasStructured && structured == null);
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
            } else if (repaired > 0) {
                // 批次12 · 12c：**按需写**。
                // repaired 是「四个字段里 trim 后与原值不同」的个数；为 0 说明库里存的就是 trim 后的值，
                // 这条 UPDATE 什么都不会改。原实现对每一行都发一次 UPDATE —— 3.5 万条实测 443 秒，
                // 而返回体显示 cleared/deduped/isolated 全为 0，即绝大多数行本来就无需改动。
                // 跳过它们不改变任何数据（同样不做 trim 之外的填充），只是不再白写。
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
            if ("合格".equals(grade) && structured != null) {
                // 人工标记读标量列（性能审查 P1-2#2）：manually_edited 是 STORED 生成列，
                // 与 _meta.manuallyEdited 同源，无需在这里再解析一遍 JSON
                if (Boolean.TRUE.equals(r.getManuallyEdited())) {
                    vo.setManualSkipped(vo.getManualSkipped() + 1);
                    // 人工修正过的按已清洗处理：它的 structured_data 已定案（方案 A 不重跑归一），
                    // 不打标记会让它永远落在「待清洗」，并被导出条件 governed=1 永久排除
                    baseMapper.markGoverned(r.getId());
                } else {
                    int[] norm = normalizeStructuredData(r, structured, dictMeta);
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
        // 清洗改写了 structured_data / pattern / governed → 统计词频过期，主动失效（B1）。
        // W1：必须落在**事务提交之后** —— 本方法带 @Transactional，提交前清缓存会让并发读
        // 按「未提交的旧数据」现算并回填，事务提交后缓存就持着旧值直到 TTL 到期（最长 60s）。
        // afterCommit 在无活动事务时立即执行，故单测直调路径行为不变。
        com.tcm.ehr.common.utils.DistLock.afterCommit(
                com.tcm.ehr.common.cache.StatsCacheInvalidator::invalidateStats);
        return vo;
    }

    /** 清洗分页大小：单页驻留多少行。1000 足够摊薄每页一次查询的开销，又不会让堆占用重新长起来 */
    private static final int CLEAN_PAGE_SIZE = 1000;

    /**
     * 惰性分页取病历（批次12 · 12c 的「流式」那一半）。
     *
     * <p>3.5 万行的堆占用从「一次全驻留」降到「一页」。依赖 {@code MybatisPlusConfig} 里已注册的
     * {@code PaginationInnerInterceptor(MYSQL)}；没有它 {@code selectPage} 会忽略分页一次返回全部。</p>
     *
     * <p><b>注意</b>：本迭代器把「取完」定义为**取到空页**。替身若每次都返回同一条非空页，
     * 迭代器会一直认为还有数据 —— 测试里要「第一次给一条、第二次给空页」。</p>
     */
    private Iterable<Record> pagedRecords(QueryWrapper<Record> wrapper) {
        return () -> new java.util.Iterator<Record>() {
            private int page = 1;
            private java.util.Iterator<Record> current = java.util.Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    com.baomidou.mybatisplus.extension.plugins.pagination.Page<Record> p =
                            new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page++, CLEAN_PAGE_SIZE);
                    p.setSearchCount(false);
                    List<Record> rows = baseMapper.selectPage(p, wrapper).getRecords();
                    if (rows.isEmpty()) {
                        return false;
                    }
                    current = rows.iterator();
                }
                return true;
            }

            @Override
            public Record next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                return current.next();
            }
        };
    }

    /**
     * 解析 structured_data（T16：校验与解析合并为同一次）。
     *
     * <p>解析不了 = 无法修复的脏数据；此时返回 {@code null}，由调用方按「不可解析」处理，
     * 不再另设一个 {@code isValidJson} 先 readTree 一遍。</p>
     *
     * @param s structured_data 列原文
     * @return 解析后的顶层 Map；不可解析返回 {@code null}
     */
    private Map<String, Object> parseStructuredData(String s) {
        try {
            return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {
            });
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * 对结构化数据的全实体补做术语归一：content 换成标准词、sourceText 保留原文，
     * 并写入 normLevel(1/2/3) 供前端溯源与三级分布统计。
     *
     * @param data     已解析的结构化数据（T16：由 {@code clean()} 解析一次后传入，本方法不再解析）
     * @param dictMeta 批级词典元数据（T13：一批只查一次库）
     * @return 依次为 被替换实体数、精确数、包含数、模糊数
     */
    private int[] normalizeStructuredData(Record r, Map<String, Object> data, DictMeta dictMeta) {
        int[] stat = {0, 0, 0, 0};
        try {
            // 1. 8 类 entity 逐类归一 + 同标准词去重（P3.4 拆出）
            normalizeEntityList(data, stat);
            // 2. 中药走另一套：name 归一 + 剂量单位小写（P3.4 拆出）
            normalizeHerbs(data, stat);
            // 3. 打上词典版本再写库：归一结果与当时词典版本必须成对
            //    T16：入参已是解析好的 Map，走 Map 重载，去掉「序列化 → 解析 → 再序列化」的回环。
            //    ⚠️ 版本源是 IDictionaryTermStore：原先用
            //    dictionaryFileService.currentVersion()（词典还在文件时代的文件哈希），
            //    批次 8b 词典入库后它已冻结 —— 清洗一次就把正确的版本戳覆盖回那个死值。
            String json = StructuredDataMeta.stamp(objectMapper, data,
                    dictMeta.version(), dictMeta.termCount());
            if (json != null) {
                baseMapper.updateStructuredData(r.getId(), json);
            } else {
                // 序列化失败：跳过回写而不是写 null，避免把结构化数据整列清空
                log.warn("[清洗] structuredData 序列化失败，跳过回写 recordId={}", r.getId());
            }
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
                    if (result.level() >= EsTermNormalizer.LEVEL_EXACT
                            && !result.standardTerm().equals(String.valueOf(content))) {
                        entity.put("content", result.standardTerm());
                        entity.put("normLevel", result.level());
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
            if (result.level() >= EsTermNormalizer.LEVEL_EXACT
                    && !result.standardTerm().equals(String.valueOf(name))) {
                herb.put("name", result.standardTerm());
                herb.put("normLevel", result.level());
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
        // 性能审查 A7：不再 filterQualified 全量入堆，改 forEachQualified 边取边写，
        // onces 内存恒定一页（1000）。JSON/CSV 两条路各自流式拼接。
        if ("json".equalsIgnoreCase(dto.getFormat())) {
            StringBuilder sb = new StringBuilder();
            final long[] exported = {0};
            sb.append('[');
            forEachQualified(dto, batch -> {
                for (Record r : batch) {
                    if (exported[0]++ > 0) {
                        sb.append(',');
                    }
                    try {
                        sb.append(objectMapper.writeValueAsString(r));
                    } catch (JacksonException e) {
                        throw new UncheckedIOException(new IOException(e));
                    }
                }
            });
            sb.append(']');
            // 范围内无合格病历时返回 null，由 Controller 转 400 + code=2001
            if (exported[0] == 0) {
                return null;
            }
            return new ExportedFile("tcm_ehr_dataset_" + ts() + ".json",
                    mask(sb.toString()).getBytes(StandardCharsets.UTF_8));
        }
        // CSV：流式拼行，再对字节打码（与旧实现同一脱敏口径）
        byte[] csv = toCsvStream(dto);
        if (csv == null) {
            return null;
        }
        return new ExportedFile("tcm_ehr_dataset_" + ts() + ".csv", maskCsv(csv));
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
            // 2. 带证候筛选：证候在 JSON 里 SQL 筛不了；流式统计 total 与样本，
            //    不再像原实现那样把全表 selectList 进堆（性能审查 A7）
            final long[] total = {0};
            sample = new ArrayList<>();
            forEachQualified(dto, batch -> {
                total[0] += batch.size();
                for (Record r : batch) {
                    if (sample.size() < PREVIEW_SAMPLE_SIZE) {
                        sample.add(r);
                    }
                }
            });
            result.put("total", total[0]);
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
        // viewAllOrgs 一并下推：清洗页的「待清洗/已清洗」必须与 clean() 实际会处理的范围同域。
        // 性能审查 P1-6 / A5：拆成 All/Org 两条 SQL，Java 侧二选一；Org 分支 orgId 取
        // domainOrgId()（无组=哨兵值），fail-closed 不会统计到别组。
        return RequestUtils.viewAllOrgs()
                ? baseMapper.selectGovernanceStatsAll()
                : baseMapper.selectGovernanceStatsOrg(RecordFilter.domainOrgId());
    }

    /**
     * 导出/预览共用的范围条件：能下推 SQL 的都下推，再追加"只含合格"这条硬约束。
     *
     * <p>证候筛选不再这里剥离、也不在内存二次筛（A8）：直接保留 {@code pattern}，
     * 由 {@link RecordFilter#build} 的「原始列 OR structured_data JSON」并集统一收窄 ——
     * 导出与列表/范围删除/统计下钻/范围扣分/批量质控共用同一个 builder，口径不再分叉。</p>
     */
    private QueryWrapper<Record> qualifiedWrapper(ExportDTO dto) {
        // 1. 条件组装复用 RecordFilter（证候并集见 RecordFilter.patternUnion）
        FiltersDTO f = RecordFilter.fromMap(dto.getFilters());
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

    /**
     * keyset 流式取「范围 + 只含合格」的病历，每批 {@link #EXPORT_PAGE_SIZE} 条交给
     * {@code batchConsumer}。证候筛选已在 SQL 层完成（A8：RecordFilter 的 pattern OR
     * structured_data 并集），这里**不再**二次内存筛，否则会与列表口径二次分叉。
     *
     * <p>性能审查 A7：formerly {@code filterQualified} 用 {@code selectList} 一次性把全部
     * 匹配行（4 万 ≈320MB）物化进堆，带证候时再逐条 {@code readValue} 4 万次。keyset 分批后
     * 内存恒定一页；实现与 RecordKeyset 的锚点语义一致（{@code visit_time DESC, id ASC}
     * 全序），不漏行不重复。</p>
     *
     * <p><b>锚点取 SQL 返回的末条</b>（含被本页筛掉的行不适用——已无内存筛，每行都是命中）。</p>
     *
     * @param dto           导出/预览请求
     * @param batchConsumer 每批回调；回调内不得改批次内容
     */
    private void forEachQualified(ExportDTO dto, Consumer<List<Record>> batchConsumer) {
        java.time.LocalDateTime cursorVt = null;
        String cursorId = null;
        boolean firstPage = true;
        while (true) {
            // 每页重建 wrapper：QueryWrapper.and() 原地追加，复用会把 WHERE 逐页累积；
            // qualifiedWrapper 含证候并集（RecordFilter.patternUnion）+ grade=合格 & governed=1
            QueryWrapper<Record> wrapper = qualifiedWrapper(dto);
            if (!firstPage) {
                RecordKeyset.anchorAfter(wrapper, cursorVt, cursorId);
            }
            List<Record> rows = baseMapper.selectList(wrapper.last("LIMIT " + EXPORT_PAGE_SIZE));
            if (rows.isEmpty()) {
                break;
            }
            batchConsumer.accept(rows);
            if (rows.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            Record last = rows.get(rows.size() - 1);
            cursorVt = last.getVisitTime();
            cursorId = last.getId();
            firstPage = false;
        }
    }

    // 原 structuredPatternContains（内存筛）已随 A8 移除：证候筛选下沉到 RecordFilter 的
    // 「pattern OR structured_data」SQL 并集，列表/导出同一口径，不再需要 JVM 侧二次过滤。

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

    /** 流式生成 CSV：表头 + 每批行（性能审查 A7）。无任何数据时返回 null（= 导出空集） */
    private byte[] toCsvStream(ExportDTO dto) throws IOException {
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
        // 3. 流式逐批逐行输出（ByteArrayOutputStream.write 不抛受检异常，lambda 内可直接写）
        final boolean[] hasRow = {false};
        forEachQualified(dto, batch -> {
            for (Record r : batch) {
                hasRow[0] = true;
                appendCsvRow(out, r, cols);
            }
        });
        return hasRow[0] ? out.toByteArray() : null;
    }

    /** 写一行 CSV：所有单元格加引号并转义内部引号/换行；就诊时间规整为 YYYY-MM-DD */
    private void appendCsvRow(ByteArrayOutputStream out, Record r, List<String> cols) {
        // T1：整实体 → Map 的转换**每行只做一次**。
        // 原先这句在下面的列循环体内，一行 22 列就要做 22 次整实体反射转换
        // （4 万行 → 数十万次反射 + 等量临时 Map，比每行转一次慢 20 倍以上）。
        // 转换结果与列名取值口径完全不变，故 CSV 内容逐字节一致。
        Map<?, ?> row = objectMapper.convertValue(r, Map.class);
        List<String> cells = new ArrayList<>();
        for (String col : cols) {
            Object v = row.get(col);
            // visitTime格式规整：展示/导出统一为YYYY-MM-DD（文档9.5③格式规整）
            if ("visitTime".equals(col) && v != null) {
                v = String.valueOf(v).substring(0, 10);
            }
            String s = v == null ? "" : String.valueOf(v).replace("\"", "\"\"").replace("\n", " ");
            cells.add("\"" + s + "\"");
        }
        out.writeBytes((String.join(",", cells) + "\n").getBytes(StandardCharsets.UTF_8));
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
