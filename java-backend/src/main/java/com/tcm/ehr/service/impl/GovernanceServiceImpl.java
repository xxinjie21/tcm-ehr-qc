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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
        // P1-3：wrapper 改为「每页重建」的工厂 —— keyset 锚点必须追加到一份干净的条件上
        // （QueryWrapper.and() 是原地追加，复用同一份会逐页累积 WHERE）。
        java.util.function.Supplier<QueryWrapper<Record>> wrapperFactory = () -> {
            boolean byIds = recordIds != null && !recordIds.isEmpty();
            // 清洗只作用于「质控合格」：按范围或按病历 ID，都在数据域之上叠加 grade=合格。
            // 按 ID 时不用范围条件（与原行为一致），只保留 ID 收窄。
            QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(),
                    qualifiedScope(byIds ? null : filters));
            return byIds ? wrapper.in("id", recordIds) : wrapper;
        };
        // P1-17 / T3：本页的 structured_data 解析结果留档，供「预热」与「逐条清洗」共用 ——
        // 预热要读同一份内容，不共用就得为预热再解析一遍（T16 的「只解析一次」不能因预热回退）。
        Map<String, Map<String, Object>> pageParsed = new HashMap<>();
        Iterable<Record> records = pagedRecords(wrapperFactory,
                page -> prefetchPage(page, RequestUtils.currentOrgId(), pageParsed));

        CleanResultVO vo = new CleanResultVO();
        // total 单独查一次：逐页再算总数是浪费。
        // 注意 selectCount 返回 Long，不能 (int) 直接强转，须 intValue()。
        vo.setTotal(baseMapper.selectCount(wrapperFactory.get()).intValue());

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
            //    P2-4：原先这里是 Arrays.asList(...).stream().filter(...).count() —— 每条病历都要
            //    建一个 List 再拉一条 Stream 管线（3.5 万条就是 3.5 万套临时对象），改成直接数。
            int clearedNow = 0;
            if (blankOnly(r.getGender())) clearedNow++;
            if (blankOnly(r.getAge())) clearedNow++;
            if (blankOnly(r.getPattern())) clearedNow++;
            if (blankOnly(r.getPrescription())) clearedNow++;
            vo.setCleared(vo.getCleared() + clearedNow);

            // 4. 脏数据隔离（收紧：仅"无法修复"）——核心文本全空 或 structuredData存在但无法解析
            //    T16：结构化数据在这里**只解析一次**，解析结果向下传给第 5 步的归一；
            //    不再让「校验（readTree）」与「归一（readValue）」各解析同一份 JSON 一遍。
            //    structured == null 同时覆盖「没有 structured_data」与「有但解析不了」两种情形。
            boolean hasStructured = r.getStructuredData() != null && !r.getStructuredData().isBlank();
            // P1-17：走本页留档的解析结果（预热时已解析过），不再解析第二遍
            Map<String, Object> structured = hasStructured ? structuredOf(pageParsed, r) : null;
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
            // P2-3：四列都没变（repaired == 0）时 r 与进循环时逐字节相同 ⇒ 哈希必然相同，
            // 直接用第 1 步那次的结果，不再重算一遍 MD5。
            // 「未改动 ⇒ 哈希不变」的前提已逐项核对：changed() 为假 ⟺ trim 后的值与库里的值
            // 完全相等（含「两者都是 null」），而 textHash 的 21 个字段里只有这四列在本循环被改写。
            String freshHash = repaired > 0 ? RecordUtil.textHash(r) : textHash;
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

    /**
     * 清洗范围条件：只作用于「质控合格」病历，<b>无视</b>传入的分级。
     *
     * <p>设计口径是「对合格数据做清洗、归一，产出标准数据集」——待复核 / 无效既不归一、
     * 也不参与导出。调用方传了别的分级也一律按「合格」收窄：范围里若按「全部」扫，
     * 「清洗总病历」会是整库条数，远大于页面顶部「质控合格病历」卡，用户看到的就是
     * 「明明只有几千合格，却清洗了几万」。</p>
     *
     * <p>刻意<b>不</b>改动入参 {@code filters}：Controller 在 {@code clean()} 之后还要用它写
     * 操作日志（{@link RecordFilter#describe}），就地改分级会让日志范围与实际执行的不一致。
     * 故这里复制一份再覆盖分级。</p>
     */
    private FiltersDTO qualifiedScope(FiltersDTO filters) {
        FiltersDTO scope = new FiltersDTO();
        if (filters != null) {
            scope.setDepartment(filters.getDepartment());
            scope.setDateRange(filters.getDateRange());
            scope.setPattern(filters.getPattern());
            scope.setIds(filters.getIds());
            scope.setOnlyUnparsed(filters.getOnlyUnparsed());
        }
        scope.setGrade("合格");
        return scope;
    }

    /** 清洗分页大小：单页驻留多少行。1000 足够摊薄每页一次查询的开销，又不会让堆占用重新长起来 */
    private static final int CLEAN_PAGE_SIZE = 1000;

    /**
     * 惰性游标分页取病历（P1-3 / AI-8 / C3）。
     *
     * <p>3.5 万行的堆占用从「一次全驻留」降到「一页」。分页本身由 keyset 承担：
     * offset 分页的末页要扫过前 3.9 万行（4 万条 40 页，累计扫描量约为实际行数的 20 倍），
     * 游标锚定上一页末条 {@code (visit_time, id)} 后是纯区间扫描（配
     * {@code idx_records_org_visit_time} 后实测 4.9s → 0.2s）。</p>
     *
     * <p>⚠️ {@code wrapperFactory} 每页调用一次：{@code QueryWrapper.and()} 是原地追加，
     * 复用同一份 wrapper 会让 WHERE 逐页累积。</p>
     *
     * <p><b>取完</b>定义为「取到空页」或「不满一页」——两者都表示没有下一页。</p>
     *
     * @param onPage 每取到一页就回调一次（**在该页任何一条被消费之前**）。
     *               P1-17 用它做「本页术语召回预热」：预热必须早于逐条归一，否则毫无意义；
     *               放在这里而不是循环体里，是因为循环体是按条消费的、看不到页边界。
     */
    private Iterable<Record> pagedRecords(java.util.function.Supplier<QueryWrapper<Record>> wrapperFactory,
                                          Consumer<List<Record>> onPage) {
        return () -> new java.util.Iterator<Record>() {
            private boolean firstPage = true;
            private boolean exhausted;
            private LocalDateTime cursorVt;
            private String cursorId;
            private java.util.Iterator<Record> current = java.util.Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext()) {
                    if (exhausted) {
                        return false;
                    }
                    QueryWrapper<Record> w = wrapperFactory.get();
                    if (!firstPage) {
                        RecordKeyset.anchorAfter(w, cursorVt, cursorId);
                    }
                    firstPage = false;
                    List<Record> rows = baseMapper.selectList(w.last("LIMIT " + CLEAN_PAGE_SIZE));
                    if (rows.isEmpty()) {
                        exhausted = true;
                        return false;
                    }
                    // P1-17：本页任何一条被消费之前先预热（解析留档 + 按类型批量问一次 ES）
                    onPage.accept(rows);
                    // 不满一页即到末尾：记下锚点后仍要把这一页发完
                    if (rows.size() < CLEAN_PAGE_SIZE) {
                        exhausted = true;
                    }
                    Record last = rows.get(rows.size() - 1);
                    cursorVt = last.getVisitTime();
                    cursorId = last.getId();
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
     * 取本页留档的解析结果；不在留档里（理论上不该发生）就现解析一次。
     *
     * <p>用 {@code containsKey} 而不是 {@code get != null} 判断：解析失败时留档的就是 {@code null}，
     * 那同样是「已解析过」的结论，不能据此再解析一遍。</p>
     */
    private Map<String, Object> structuredOf(Map<String, Map<String, Object>> pageParsed, Record r) {
        if (pageParsed.containsKey(r.getId())) {
            return pageParsed.get(r.getId());
        }
        return parseStructuredData(r.getStructuredData());
    }

    /**
     * 按页预热术语召回（P1-17 / T3）：把本页所有待归一原文按类型收集好，一类一次问完 ES。
     *
     * <p><b>改的是什么</b>：清洗链路原先逐实体调 {@code normalize}，每个实体一次 ES 往返 ——
     * 一页 1000 条病历、每条十几个实体，就是上万次往返。这里在<b>本页任何一条被归一之前</b>
     * 先批量问一次，随后逐条归一全部命中召回缓存。复用解析链路既有的
     * {@link EsTermNormalizer#prefetch}（内部走 {@code _msearch}），不新增批量查询接口。</p>
     *
     * <p><b>不改的是什么</b>：预热只写召回缓存、不参与判定。命中与否仍由三级规则与 Dice 阈值决定，
     * 预取失败也不影响正确性（逐条归一照常走 ES）。</p>
     *
     * <p><b>只预热有独立词典的 5 类</b>（疾病 / 证候 / 症状 / 中药 / 方剂）。舌象、脉象、病因、治法
     * 没有词典，清洗链路对它们根本不调 ES（见 {@link #normalizeEntityList}），预热没有意义。</p>
     *
     * <p>预热粒度是「一页」而非整批：召回缓存本身有容量上限（{@code RECALL_CACHE_MAX}），
     * 一次问进远超用量的术语只会把先问进来的挤出去。一页正好是「问进来就马上用掉」的窗口。</p>
     *
     * <p>顺带把本页的 {@code structured_data} 解析结果留在 {@code pageParsed} 里供逐条清洗复用 ——
     * 预热要读同一份内容，不共用就得为预热再解析一遍（T16 的「只解析一次」不能因预热回退）。</p>
     */
    private void prefetchPage(List<Record> page, String orgId,
                              Map<String, Map<String, Object>> pageParsed) {
        // 0. 只留本页的解析结果：回调一定发生在上页被消费完之后，这里清掉不会影响上页；
        //    不清的话整批 4 万条的解析结果会一直挂在内存里 —— 那正是 12c 要消掉的堆占用。
        pageParsed.clear();
        // 1. 逐条解析本页 structured_data（留档复用），并按类型收集待归一原文
        Map<String, Set<String>> rawsByType = new HashMap<>();
        for (Record r : page) {
            String json = r.getStructuredData();
            if (json == null || json.isBlank()) {
                continue;
            }
            Map<String, Object> data = parseStructuredData(json);
            pageParsed.put(r.getId(), data);
            if (data == null) {
                continue;
            }
            // 2. 有词典的 4 类 entity：原文取 content（与 normalizeEntityList 同口径）
            for (String key : List.of("diseases", "symptoms", "patternList", "formulaList")) {
                String type = mapEntityType(key);
                if (type != null) {
                    collectEntityRaws(rawsByType, type, data.get(key), "content");
                }
            }
            // 3. 中药是另一套：原文取 name（与 normalizeHerbs 同口径）
            collectEntityRaws(rawsByType, "herb", data.get("herbs"), "name");
        }
        // 4. 一类一趟 _msearch；结果写进与单条 search 相同的缓存键，随后逐条归一直接命中
        rawsByType.forEach((type, raws) -> termNormalizer.prefetch(type, orgId, new ArrayList<>(raws)));
    }

    /** 从结构化数据的某一类实体里收集待归一原文（实体取 content、中药取 name），同批内去重 */
    @SuppressWarnings("unchecked")
    private void collectEntityRaws(Map<String, Set<String>> out, String type, Object listObj, String termField) {
        // 1. 该类不是列表（缺失 / 脏数据）就没得预热
        if (!(listObj instanceof List<?> list)) {
            return;
        }
        Set<String> raws = out.computeIfAbsent(type, k -> new LinkedHashSet<>());
        // 2. 逐条取原文：空值 / 空白跳过（归一自己会判空，预热不必带上）
        for (Object item : list) {
            if (!(item instanceof Map)) continue;
            Object v = ((Map<String, Object>) item).get(termField);
            if (v == null) continue;
            String s = String.valueOf(v);
            if (!s.isBlank()) {
                raws.add(s);
            }
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
     * 标准数据集导出：只含质控合格病历，文本字段已就地标准化，并对手机号/身份证号脱敏。
     *
     * <p><b>「标准数据集」的含义</b>：导出的不是库内原文的拷贝，而是把每条病历里
     * 「被归一过的术语」在原文中就替换成标准词之后的版本（见 {@link #standardizeMap}）。
     * 这样一份导出件可以直接当训练/统计语料用，不必再对一遍词典。库内原文不受影响 ——
     * 本链路只读。</p>
     *
     * <p>CSV 额外带一列「标准化对照」，记录该行实际发生的替换，便于逐条回溯到原文。</p>
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
                        // 标准数据集：把这条病历里「被归一过」的术语在文本中就地换成标准词。
                        // 只改导出件，库里的原文一个字节都不动（本链路纯只读）。
                        sb.append(objectMapper.writeValueAsString(
                                standardizeMap(r, replacementsOf(r))));
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
     * 样本按导出同一口径处理：先就地标准化、再序列化、再打码、最后解析回结构，键名与类型不变。
     *
     * <p>标准化必须在这里也做一遍，否则「预览里看到的还是原文、下载下来却是标准词」——
     * 预览的意义就是让用户先确认导出件长什么样。</p>
     *
     * <p>刻意不捕获异常：脱敏失败就让这次预览失败，而不是静默返回未脱敏的样本。</p>
     */
    private Object maskedSample(List<Record> sample) {
        List<Map<String, Object>> standardized = new ArrayList<>(sample.size());
        for (Record r : sample) {
            standardized.add(standardizeMap(r, replacementsOf(r)));
        }
        String json = objectMapper.writeValueAsString(standardized);
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
    // P2-4：预编译。原先用 String.replaceAll —— 它每次调用内部都要 Pattern.compile 一遍，
    // 而这两条表达式是常量（一次导出要对整份 JSON / CSV 各打一次码）。
    private static final Pattern PHONE_RE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_RE = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    private String mask(String s) {
        // 两次替换的先后不能调换：先手机号后身份证，与改造前逐字节一致
        return ID_RE.matcher(PHONE_RE.matcher(s).replaceAll("***")).replaceAll("***");
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
        // 1. 表头与列顺序一一对应，改一处必须改另一处。
        //    最后一列「标准化对照」不在 21 个原始字段内，是导出附加的溯源列（见 appendCsvRow）
        String[] headers = {"id", "挂号号", "门诊号", "性别", "年龄", "就诊次数", "西医诊断", "中医诊断",
                "现病史", "主诉", "自述", "望诊", "脉象", "舌象", "体格检查", "辨证结论", "处方",
                "随访", "治疗效果", "科室", "医生ID", "就诊时间", "标准化对照"};
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
                appendCsvRow(out, r, cols, replacementsOf(r));
            }
        });
        return hasRow[0] ? out.toByteArray() : null;
    }

    /**
     * 写一行 CSV：所有单元格加引号并转义内部引号/换行；就诊时间规整为 YYYY-MM-DD。
     *
     * <p>文本单元格先按 {@code pairs} 就地标准化，最后一列固定写「标准化对照」。</p>
     */
    private void appendCsvRow(ByteArrayOutputStream out, Record r, List<String> cols,
                              List<Map.Entry<String, String>> pairs) {
        // T1：整实体 → Map 的转换**每行只做一次**。
        // 原先这句在下面的列循环体内，一行 22 列就要做 22 次整实体反射转换
        // （4 万行 → 数十万次反射 + 等量临时 Map，比每行转一次慢 20 倍以上）。
        // 转换结果与列名取值口径完全不变，故 CSV 内容逐字节一致。
        // 标准化在这一步一起做掉（同一份 Map，不额外多一次转换）。
        Map<?, ?> row = standardizeMap(r, pairs);
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
        // 溯源列：本条病历的「归一前原文 → 标准词」对照；没有归一结果就是 {}。
        // 单独一列而不是每字段两列 —— 21 个字段里真正会被替换的通常只有少数几个，
        // 拆成 42 列会让表宽翻倍且绝大多数格子是空的。
        String trace = pairsJson(pairs).replace("\"", "\"\"").replace("\n", " ");
        cells.add("\"" + trace + "\"");
        out.writeBytes((String.join(",", cells) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 导出标准化

    /**
     * 取一条病历的「归一前原文 → 标准词」替换对，按原词长度<b>降序</b>。
     *
     * <p>只用该病历自己的 {@code structured_data}：归一结果本来就是逐条产生的，
     * 别条病历抽到的术语在这条病历里没有对应的原文片段，硬套没有依据。
     * 反过来这也让替换量恒定在「本条病历的实体数」量级，不会随数据集变大而变慢。</p>
     *
     * <p>降序是为了长词优先 —— 否则「柴胡」会先把「柴胡桂枝汤」打成「标准柴胡名+桂枝汤」。</p>
     */
    private List<Map.Entry<String, String>> replacementsOf(Record r) {
        String json = r.getStructuredData();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        Map<String, String> table = new LinkedHashMap<>();
        try {
            Map<String, Object> data = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {
                    });
            collectReplacements(data, table);
        } catch (JacksonException e) {
            // 单条脏数据不该让整份导出失败：这条不标准化（保持原文）即可
            log.warn("[导出] structured_data 解析失败，该条不做标准化 recordId={}: {}",
                    r.getId(), e.getMessage());
            return List.of();
        }
        if (table.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, String>> pairs = new ArrayList<>(table.entrySet());
        pairs.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        return pairs;
    }

    /** 8 类实体取 {@code content}，中药取 {@code name}；两类都带 {@code sourceText} */
    @SuppressWarnings("unchecked")
    private static void collectReplacements(Map<String, Object> data, Map<String, String> table) {
        for (String key : List.of("diseases", "symptoms", "tongueList", "pulseList", "patternList",
                "causeList", "treatmentList", "formulaList")) {
            collectPairs(data.get(key), "content", table);
        }
        collectPairs(data.get("herbs"), "name", table);
    }

    /** 从一个实体列表里收「sourceText → 标准词」；相同原文映射到不同标准词时保留先出现的 */
    private static void collectPairs(Object listObj, String termField, Map<String, String> table) {
        if (!(listObj instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String raw = trimmed(m.get("sourceText"));
            String std = trimmed(m.get(termField));
            // 没有原文、没有标准词、或本来就一样（未命中词典）都不构成一次替换
            if (raw == null || std == null || raw.equals(std)) {
                continue;
            }
            // 同一原文被归一到两个不同标准词，说明该原文有歧义 —— 此时不替换比乱替换安全
            table.putIfAbsent(raw, std);
        }
    }

    /**
     * 会被标准化的字段：医生手写的文本字段 —— 21 个原始字段里除编号 / 性别 / 年龄 /
     * 就诊次数 / 科室 / 工号 / 接诊时间之外的 13 个。
     *
     * <p>刻意<b>不</b>做成「所有字符串字段都过一遍」：那样 {@code department} 这类短文本
     * 会被误伤 —— 例如术语「内科 → 中医内科」会把 {@code department="中医内科"} 替换成
     * 「中医中医内科」。漏替换只是少做一步，误替换是污染数据，两者不对等。</p>
     *
     * <p>顺带也把 {@code structuredData} 挡在外面：它里面的 {@code sourceText} 正是
     * 「这条病历原本怎么写」的唯一依据，一起替换掉就等于把溯源信息抹了。</p>
     */
    private static final List<String> STANDARDIZABLE_FIELDS = List.of(
            "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint", "selfReport",
            "inspection", "pulse", "tongue", "physicalExam", "pattern", "prescription",
            "followUp", "treatmentEffect");

    /**
     * 把一条病历转成 Map 并对上述文本字段做就地标准化（<b>不改动入参 Record</b>）。
     *
     * <p>用 {@code convertValue} 而不是直接改 Record：导出的记录对象可能还在 MyBatis
     * 一级缓存里，就地改会污染后续读取。</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> standardizeMap(Record r, List<Map.Entry<String, String>> pairs) {
        Map<String, Object> row = objectMapper.convertValue(r, Map.class);
        if (pairs.isEmpty()) {
            return row;
        }
        for (String field : STANDARDIZABLE_FIELDS) {
            Object v = row.get(field);
            if (v instanceof String s) {
                row.put(field, standardizeText(s, pairs));
            }
        }
        return row;
    }

    /** 按替换对做文本内替换（字面量，非正则）；没有命中就原样返回 */
    private static String standardizeText(String s, List<Map.Entry<String, String>> pairs) {
        if (s == null || s.isEmpty() || pairs.isEmpty()) {
            return s;
        }
        String out = s;
        for (Map.Entry<String, String> p : pairs) {
            if (out.contains(p.getKey())) {
                out = out.replace(p.getKey(), p.getValue());
            }
        }
        return out;
    }

    /** 替换对的 JSON 形式（溯源列内容）；无替换时返回 {@code {}} */
    private String pairsJson(List<Map.Entry<String, String>> pairs) {
        if (pairs.isEmpty()) {
            return "{}";
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> p : pairs) {
            m.put(p.getKey(), p.getValue());
        }
        return objectMapper.writeValueAsString(m);
    }

    /** 取字符串字段并 trim；空 / 非字符串返回 null */
    private static String trimmed(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private String trim(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    private boolean changed(String oldV, String newV) {
        return oldV != null && !oldV.equals(newV);
    }

    /** 非空但仅由空白构成（trim 后为空）—— 「空值规整」的计数口径，与第 3 步逐字一致 */
    private static boolean blankOnly(String v) {
        return v != null && !v.isEmpty() && v.trim().isEmpty();
    }

    /** 导出文件名用毫秒精度：秒级会让同一秒内的两次导出得到同名文件 */
    private String ts() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
    }
}
