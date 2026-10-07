package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.ExcelRawStreamReader;
import java.io.IOException;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.common.utils.ReviewTaskUtil;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.dto.CreateRecordDTO;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CreateRecordVO;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.domain.vo.ImportSummaryVO;
import com.tcm.ehr.domain.vo.ImportTaskVO;
import com.tcm.ehr.domain.vo.RawRecordVO;
import com.tcm.ehr.domain.vo.SearchVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 病历数据服务实现：Excel 批量导入 + 单条新增。
 *
 * <ul>
 * <li>解析：POI {@link WorkbookFactory}（兼容 .xlsx / .xls）；表头中文名 → 21 字段；</li>
 * <li>去重：复用 {@link RecordUtil#textHash}（21 字段固定顺序 MD5），与数据清洗同口径；
 *     并写入 {@code text_hash} 列，DB 侧 {@code uk_records_org_text_hash} 兜底并发重复；</li>
 * <li>导入<b>同步执行</b>：接口返回即本轮完成，status 直接为「已完成」。</li>
 * </ul>
 *
 * <p><b>进度查询已删除</b>：原「内存 Map 记 taskId + 查进度」的那套状态存储与
 * {@code GET /api/records/import/{taskId}/status} 端点已在 P5.11 移除（导入是同步的，
 * 内存进度表没有任何读取方）。进度由前端按文件逐个统计，不再向服务端要状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordServiceImpl extends ServiceImpl<RecordMapper, Record> implements IRecordService {

    private final ObjectMapper objectMapper;
    private final com.tcm.ehr.service.INlpBatchService nlpBatchService;
    private final com.tcm.ehr.mapper.ReviewTaskMapper reviewTaskMapper;
    /** 写回结构化数据时打词典版本戳（与解析链路同口径，保证可追溯） */
    private final com.tcm.ehr.service.IDictionaryTermStore termStore;

    /** 原始 21 字段（禁止通过修改接口变更，命中即 400） */
    private static final Set<String> ORIGINAL_FIELDS = Set.of(
            "registrationNo", "outpatientNo", "gender", "age", "visitCount",
            "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint", "selfReport",
            "inspection", "pulse", "tongue", "physicalExam", "pattern", "prescription",
            "followUp", "treatmentEffect", "department", "doctorId", "visitTime");
    private static final int MAX_FILES = 20;

    /** 21 列表头中文名 → 字段标识 已随批次 13 · 13.3 搬到 {@link ExcelHeaderFields} */

    
    

    /**
     * Excel 批量导入病历（同步执行，返回即本轮完成）。
     *
     * <p>逐文件校验扩展名、大小（≤50MB）与必需列「登记号」「接诊时间」，逐行映射为 21 字段；
     * 缺列、解析失败、门诊号为空的行计入失败明细，不影响其余行。去重先按登记号预取库内病历哈希、
     * 再与本批内哈希比对（与数据清洗同口径的 21 字段文本哈希），命中的按重复记失败。落库走
     * {@code saveBatch} 一次批量插入。导入是同步的，
     * {@code autoExtract} 且有成功记录时，另行提交后台 NLP 批解析任务，导入本身不阻塞等待。</p>
     *
     * @param files 上传的 Excel 文件数组（最多 20 个）
     * @param autoExtract 是否在导入后自动提交结构化解析任务
     * @return 任务 ID、导入摘要（总数 / 成功 / 失败明细）与自动解析任务 ID（未提交为 null）
     * @throws IllegalArgumentException 未上传文件或文件数超过上限时抛出
     */
    @Override
    public ImportTaskVO importRecords(MultipartFile[] files, boolean autoExtract) {
        // 1. 入参校验：至少一个文件，且不超过单次上限
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请上传至少一个文件");
        }
        if (files.length > MAX_FILES) {
            throw new IllegalArgumentException("单次最多上传 " + MAX_FILES + " 个文件");
        }

        // 2. 任务 ID：仅作本次请求的回执标识（P5.11：导入是同步的，不再维护内存进度表）
        String taskId = UUID.randomUUID().toString();

        // 3. 初始化导入摘要与批内去重容器
        ImportSummaryVO summary = new ImportSummaryVO();
        List<Record> toInsert = new ArrayList<>();
        Map<String, String> regNoSource = new HashMap<>();

        // 预取本批登记号对应的库内病历哈希，用于跨批去重（不必全表扫描）
        Set<String> batchRegNos = new HashSet<>();

        List<Object[]> parsedRows = new ArrayList<>(); // [Record, filename]

        // 「接诊时间」有原值却解析不出来的行数与前若干条样例。
        // 这类行照旧入库（见 parseDateTime 的取舍），但必须留痕 —— 见文件循环之后的 warn
        int[] visitTimeWarn = {0};
        List<String> visitTimeWarnSamples = new ArrayList<>();

        // 4. 逐文件处理（P3.4：单文件解析抽到 parseFile）
        for (MultipartFile file : files) {
            ExcelSheetImporter.importFile(file, summary, parsedRows, batchRegNos, visitTimeWarn, visitTimeWarnSamples);
        }

        // 「接诊时间」解析失败不阻断导入，但必须留痕：只写日志、不改接口与摘要，
        // 因为「有值却认不出」既不是失败也不是跳过，塞进 failures 会让「失败/跳过」计数自相矛盾
        if (visitTimeWarn[0] > 0) {
            log.warn("[导入] {} 行的「接诊时间」格式无法识别，已按空值入库 —— 这些病历不会进就诊趋势、"
                            + "按接诊时间筛选也筛不出来，请核对导入源。示例：{}",
                    visitTimeWarn[0], String.join("、", visitTimeWarnSamples));
        }

        // 库内已存在哈希（按登记号预筛，避免全表扫描）
        Set<String> existingHash = new HashSet<>();
        if (!batchRegNos.isEmpty()) {
            // § 6.3 缺点 12：查重必须限定本组。否则组 A 已有登记号 X，
            // 组 B 导入同号会被判为重复，两个组无法使用相同登记号。
            List<Record> existing = baseMapper.selectList(
                    new QueryWrapper<Record>()
                            .in("registration_no", batchRegNos)
                            .eq("org_id", RequestUtils.currentOrgId()));
            for (Record r : existing) {
                existingHash.add(RecordUtil.textHash(r));
            }
        }

        // 6. 逐行去重并收进待插入列表（P3.4 拆出 dedupeAndCollect）
        dedupeAndCollect(parsedRows, existingHash, summary, toInsert);

        // 7. 批量落库并回填成功数。
        //    并发导入同一份文件时，上面的「先查后插」会双双通过，其中一个撞
        //    uk_records_org_text_hash —— 整批抛出去会让「已入库的前若干行」与回执对不上
        //    （用户看到失败，库里却已有数据）。这里退化为逐行插入：撞键的行计入失败明细，
        //    其余行照常入库，成功数与库内实际新增一致。
        if (!toInsert.isEmpty()) {
            try {
                saveBatch(toInsert);
                summary.setSuccess(toInsert.size());
            } catch (DataIntegrityViolationException e) {
                log.warn("[病历导入] 批量插入撞唯一键，退化为逐行插入并逐行记失败: {}", e.getMessage());
                int inserted = 0;
                for (Record r : toInsert) {
                    try {
                        baseMapper.insert(r);
                        inserted++;
                    } catch (DataIntegrityViolationException dup) {
                        summary.setFailed(summary.getFailed() + 1);
                        summary.getFailures().add(new ImportSummaryVO.Failure("(并发导入)",
                                "重复病历（撞唯一键）：登记号 " + r.getRegistrationNo()));
                    }
                }
                summary.setSuccess(inserted);
            }
        }

        // 导入后自动结构化解析（用户开关，默认关）：投后台批任务，导入本身不阻塞
        String autoTaskId = null;
        if (autoExtract && !toInsert.isEmpty()) {
            try {
                var task = nlpBatchService.submitIds(
                        toInsert.stream().map(Record::getId).toList(), RequestUtils.currentUsername());
                autoTaskId = task == null ? null : task.getId();
            } catch (Exception e) {
                log.warn("[病历导入] 自动解析未提交：{}", e.getMessage());
            }
        }

        // 8. 回填任务状态、摘要与返回体
        ImportTaskVO vo = new ImportTaskVO();
        vo.setTaskId(taskId);
        vo.setSummary(summary);
        vo.setAutoExtractTaskId(autoTaskId);
        log.info("[病历导入] task={} 文件={} 行={} 成功={} 失败={}",
                taskId, files.length, summary.getTotal(), summary.getSuccess(), summary.getFailed());
        return vo;
    }

    /** 逐行去重（库内哈希 + 批内哈希双查）后收进待插入列表；重复记失败明细（P3.4 从 importRecords 抽出） */
    private void dedupeAndCollect(List<Object[]> parsedRows, Set<String> existingHash,
                                  ImportSummaryVO summary, List<Record> toInsert) {
        Set<String> seenHash = new HashSet<>();
        for (Object[] item : parsedRows) {
            Record r = (Record) item[0];
            String filename = (String) item[1];
            String hash = RecordUtil.textHash(r);
            if (existingHash.contains(hash) || !seenHash.add(hash)) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename,
                        "重复病历（21 字段完全一致）：登记号 " + r.getRegistrationNo()));
                continue;
            }
            r.setId(UUID.randomUUID().toString());
            // 新建病历入组织（否则是无组织病历，导入者导完自己也看不到）
            r.setOrgId(RequestUtils.currentOrgId());
            // 去重兜底：写 text_hash 让 DB 的 uk_records_org_text_hash 生效。
            // 不写的话该列恒 NULL，唯一键形同虚设（批次 4 随 DDL 一起补的代码路径）
            r.setTextHash(RecordUtil.textHash(r));
            toInsert.add(r);
        }
    }

    /**
     * 单条新增病历。
     *
     * <p>仅做「登记号 / 门诊号非空」的必填校验，不做重复校验；主键由服务端生成 UUID，
     * 21 个原始字段原样落库。</p>
     *
     * @param dto 新增请求，登记号与门诊号必填
     * @return 新病历 ID
     * @throws IllegalArgumentException 登记号或门诊号为空时抛出
     */
    @Override
    public CreateRecordVO createRecord(CreateRecordDTO dto) {
        // 1. 必填校验：登记号
        if (dto == null || TextUtil.isBlank(dto.getRegistrationNo())) {
            throw new IllegalArgumentException("请填写登记号");
        }
        // 2. 必填校验：门诊号
        if (TextUtil.isBlank(dto.getOutpatientNo())) {
            throw new IllegalArgumentException("请填写门诊号");
        }
        // 3. 组装病历实体：主键由服务端生成，21 个原始字段原样落库；入本组
        Record r = new Record();
        r.setId(UUID.randomUUID().toString());
        r.setOrgId(RequestUtils.currentOrgId());
        r.setRegistrationNo(dto.getRegistrationNo());
        r.setOutpatientNo(dto.getOutpatientNo());
        r.setGender(dto.getGender());
        r.setAge(dto.getAge());
        r.setVisitCount(dto.getVisitCount());
        r.setWesternDiagnosis(dto.getWesternDiagnosis());
        r.setTcmDiagnosis(dto.getTcmDiagnosis());
        r.setPresentIllness(dto.getPresentIllness());
        r.setChiefComplaint(dto.getChiefComplaint());
        r.setSelfReport(dto.getSelfReport());
        r.setInspection(dto.getInspection());
        r.setPulse(dto.getPulse());
        r.setTongue(dto.getTongue());
        r.setPhysicalExam(dto.getPhysicalExam());
        r.setPattern(dto.getPattern());
        r.setPrescription(dto.getPrescription());
        r.setFollowUp(dto.getFollowUp());
        r.setTreatmentEffect(dto.getTreatmentEffect());
        r.setDepartment(dto.getDepartment());
        r.setDoctorId(dto.getDoctorId());
        r.setVisitTime(dto.getVisitTime());
        // 3.1 初始状态：新入库的病历还没跑质控。写 pending 而不是留空 ——
        //     status 的取值集是 pending/reviewing/completed/invalid，留空等于第四个「看不见」的值
        r.setStatus("pending");
        // 4. 去重兜底：与导入同口径算 text_hash，让唯一键能拦住重复单条新增
        r.setTextHash(RecordUtil.textHash(r));
        // 5. 落库
        baseMapper.insert(r);

        // 5. 只回传新病历 ID
        CreateRecordVO vo = new CreateRecordVO();
        vo.setId(r.getId());
        return vo;
    }

    /**
     * 查询单条原始病历（含结构化数据与质控结果），只读。
     *
     * <p>先按组织数据域校验：不满足即拒绝而非返回空。
     * 病历不存在时返回 null。</p>
     *
     * @param recordId 病历 ID
     * @return 原始病历视图；不存在时为 null
     * @throws ForbiddenException 访问非本组织病历时抛出
     */
    @Override
    public RawRecordVO getRawRecord(String recordId) {
        // 1. 按 id 取病历，不存在返回 null（由上层转 404）
        Record r = baseMapper.selectById(recordId);
        if (r == null) {
            return null;
        }
        // § 6.3 缺点 4：查后校验组。无组 / 不属于当前组统一归“不存在”（404）：
        // 遏免“存在但看不到”被用作情报（可探测别组病历 ID）
        if (!RecordFilter.canAccess(r)) {
            return null;
        }
        // 2. 组装视图：21 个原始字段 + 结构化数据 + 评分结果
        RawRecordVO vo = new RawRecordVO();
        vo.setId(r.getId());
        vo.setRegistrationNo(r.getRegistrationNo());
        vo.setOutpatientNo(r.getOutpatientNo());
        vo.setGender(r.getGender());
        vo.setAge(r.getAge());
        vo.setVisitCount(r.getVisitCount());
        vo.setWesternDiagnosis(r.getWesternDiagnosis());
        vo.setTcmDiagnosis(r.getTcmDiagnosis());
        vo.setPresentIllness(r.getPresentIllness());
        vo.setChiefComplaint(r.getChiefComplaint());
        vo.setSelfReport(r.getSelfReport());
        vo.setInspection(r.getInspection());
        vo.setPulse(r.getPulse());
        vo.setTongue(r.getTongue());
        vo.setPhysicalExam(r.getPhysicalExam());
        vo.setPattern(r.getPattern());
        vo.setPrescription(r.getPrescription());
        vo.setFollowUp(r.getFollowUp());
        vo.setTreatmentEffect(r.getTreatmentEffect());
        vo.setDepartment(r.getDepartment());
        vo.setDoctorId(r.getDoctorId());
        vo.setVisitTime(r.getVisitTime());
        vo.setStructuredData(r.getStructuredData());
        vo.setScore(r.getScore());
        vo.setGrade(r.getGrade());
        vo.setStatus(r.getStatus());
        // 复核提交的乐观并发校验：读时算指纹，提交时比对（批次 25.15）
        vo.setFingerprint(ReviewTaskUtil.fingerprint(r.getStructuredData(), r.getScore(), r.getGrade()));
        return vo;
    }

    /**
     * 更新病历的结构化数据（原始 21 字段只读）。
     *
     * <p>请求体若显式携带任一原始字段即整体拒绝；仅接受 {@code structuredData}，序列化后打上
     * 当前词典版本戳再落库，保证结构化结果可追溯。</p>
     *
     * @param recordId 病历 ID
     * @param body 请求体，需含 structuredData
     * @throws IllegalArgumentException 病历不存在、携带原始字段、缺少 structuredData 或序列化失败时抛出
     */
    @Override
    public void updateRecord(String recordId, Map<String, Object> body) {
        // 1. 取病历，不存在即拒绝
        Record r = baseMapper.selectById(recordId);
        if (r == null) {
            throw new IllegalArgumentException("病历不存在");
        }
        // § 6.3 缺点 5：不能改别组病历。用 ForbiddenException而不是 404：
        // 这是写操作，攻击者看到的应是「不许」而不是「不存在」。
        if (!RecordFilter.canAccess(r)) {
            throw new ForbiddenException("无权修改该病历");
        }
        // 原始 21 字段只读：显式携带原始字段即拒绝（由 GlobalExceptionHandler 统一转 400）
        if (body != null) {
            for (String key : body.keySet()) {
                if (ORIGINAL_FIELDS.contains(key)) {
                    throw new IllegalArgumentException("原始字段不允许修改");
                }
            }
        }
        // 2. 只接受 structuredData，缺失即拒绝
        Object sd = body == null ? null : body.get("structuredData");
        if (sd == null) {
            throw new IllegalArgumentException("未提供结构化数据");
        }
        // 3. 序列化结构化数据（格式错误转成参数错误，不落到 500）
        String json;
        try {
            json = objectMapper.writeValueAsString(sd);
        } catch (Exception e) {
            throw new IllegalArgumentException("structuredData 格式错误");
        }
        // 4. 落库：打上当前词典版本戳（与解析链路的 processOne 同口径），
        //    这样这份结构化数据「依据哪一版词典」可追溯。
        //    这条路径是解析页的「写回结构化数据」：内容来自模型抽取 / 规则兜底，是模型产出，
        //    不是人工修正 —— 打 manuallyEdited 会让「评估模型准确率时排除人工补过的数据」
        //    这个唯一用途失真，而且清洗会按方案 A 永久跳过该病历。
        //    人工修改标记只由复核路径（correctedData）写，见 ReviewServiceImpl。
        json = StructuredDataMeta.stamp(objectMapper, json,
                termStore.effectiveDictVersion(RequestUtils.currentOrgId()),
                termStore.effectiveTermCount(RequestUtils.currentOrgId()));
        baseMapper.updateStructuredData(recordId, json);
    }

    /**
     * 按 id 删除。
     *
     * <p>事务加在这个 public 方法上，<b>不能</b>加在 private 的 {@code doDelete} 上 ——
     * 后者只被同类自调用，注解不经过代理、等于没加
     * （{@code fk_review_record} 存在，先删 review_tasks 再删 records，中途失败会留下孤儿状态）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public DeleteRecordsVO deleteRecords(DeleteRecordsDTO dto) {
        // 批次13 · 13.3：删除逻辑抽到 RecordDeleter（防误删守卫、数据域过滤、分块、外键顺序都在那边）。
        // 事务仍留在本方法上：加在被调用方或私有方法上不经过代理，等于没加。
        return deleter().deleteByIds(dto);
    }

    /**
     * 取删除器。
     *
     * <p>用继承来的 {@code baseMapper} **字段**而不是 {@code getBaseMapper()}：后者在 mapper 为 null 时
     * 自己就抛 MybatisPlusException，而那会让「防误删守卫先于任何 mapper 访问」这条设计失效
     * （守卫用例期望 IllegalArgumentException，实测拿到的是 MybatisPlusException）。读字段不抛异常，
     * 守卫得以在真正需要 mapper 之前先判并拒绝。</p>
     */
    private RecordDeleter deleter() {
        return new RecordDeleter(baseMapper, reviewTaskMapper);
    }

    /**
     * 按当前数据域筛出可访问的病历 id、分块删除、以及「是否有筛选条件」的判定，
     * 都已随批次 13 · 13.3 一起搬到 {@link RecordDeleter}。
     *
     * <p>保留这两个入口在此类，是因为它们是 {@code IRecordService} 的接口方法，
     * 而且事务必须加在这里的 public 方法上（见上）。</p>
     */
    @Transactional(rollbackFor = Exception.class, timeout = 60)
    @Override
    public DeleteRecordsVO deleteByFilter(FiltersDTO filters) {
        // 性能审查 A4：整个游标分批删除在一个事务内（all-or-nothing）。timeout=60s 是防呆：
        // 4 万条删除 + 连带 review_tasks 的单事务，长于 innodb_lock_wait_timeout(默认50s) 就先被
        // 锁等待坑掉；超时只保护「误操作长时间锁表」，不是对条数的限制。
        return deleter().deleteByFilter(filters);
    }

    /**
     * 分页检索病历列表，只读。
     *
     * <p>分页参数非法时回退为第 1 页 / 每页 20 条；查询条件统一经 {@link RecordFilter} 组装
     * （先数据域、后用户筛选取交集），不手写 where。列表项摘要取主诉，缺失时依次回退中医诊断、
     * 西医诊断，超过 40 字截断。</p>
     *
     * @param dto 检索请求（分页 + 筛选），可为 null
     * @return 命中总数与当前页列表项
     */
    @Override
    public SearchVO searchRecords(SearchDTO dto) {
        // 1. 分页参数非法时回退为第 1 页 / 每页 20 条
        int page = dto != null && dto.getPage() != null && dto.getPage() > 0 ? dto.getPage() : 1;
        int size = dto != null && dto.getPageSize() != null && dto.getPageSize() > 0 ? dto.getPageSize() : 20;
        // 数据域 → 用户筛选，取交集（统一走 RecordFilter，禁止手写 where）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(), dto);
        // ⚠️ 列投影（性能审查 P1-2）：列表项只消费 id/grade/visitTime/gender/age/score/
        //    summarize 三字段回退链（主诉→中医诊断→西医诊断）/structured_data（manual 标记），
        //    其余 21 个 TEXT 列 + qc_results 不拉进堆（每行省 ~5KB）。
        //    列名与 RecordFilter / Record 实体列一致，改动时同步维护 RecordServiceImplTest 的列名守卫。
        //    刻意保留 structured_data：manuallyEdited 仍靠解析 _meta 取（落列方案未本轮）。
        wrapper.select("id", "grade", "visit_time", "gender", "age", "score",
                "chief_complaint", "tcm_diagnosis", "western_diagnosis", "structured_data");
        // 2. 分页查询（条件已含数据域与用户筛选）
        Page<Record> p = baseMapper.selectPage(new Page<>(page, size), wrapper);
        // 3. 组装返回：总数与当前页列表项
        SearchVO vo = new SearchVO();
        vo.setTotal(p.getTotal());
        for (Record r : p.getRecords()) {
            // manuallyEdited 从 structured_data._meta 读：列表要能一眼看出「这条不是模型原样抽的」
            boolean manual = StructuredDataMeta.isManuallyEdited(objectMapper, r.getStructuredData());
            vo.getRecords().add(new SearchVO.Item(r.getId(), summarize(r), r.getGrade(),
                    r.getVisitTime(), r.getGender(), r.getAge(), r.getScore(), manual));
        }
        return vo;
    }

    /**
     * 只统计筛选范围内的病历总数（与 {@link #searchRecords} 同一 wrapper 口径）。
     * <p>性能审查 P1-5：原「只取 total」用 {@code pageSize=1} 走 SELECT —— 即便只回 1 行，
     * 后端仍会对 4 万行做一次 filesort/读全表；改成 {@code selectCount} 后是纯 COUNT。</p>
     */
    @Override
    public long countMatched(SearchDTO dto) {
        // 数据域 + 用户筛选，统一走 RecordFilter（与 searchRecords 完全相同，禁止手写 where）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentOrgId(), dto);
        // COUNT 不关心 ORDER BY / SELECT 列，表结构有 (org_id) 覆盖后仅 5ms
        return baseMapper.selectCount(wrapper);
    }

    private String summarize(Record r) {
        // 1. 主诉优先，缺了依次回退中医诊断、西医诊断
        String s = r.getChiefComplaint();
        if (TextUtil.isBlank(s)) {
            s = r.getTcmDiagnosis();
        }
        if (TextUtil.isBlank(s)) {
            s = r.getWesternDiagnosis();
        }
        if (s == null) {
            return "";
        }
        // 2. 去空白并截到 40 字：列表页只放得下一行
        s = s.trim();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // ============ 解析辅助 ============


    /**
     * 接诊时间解析、单元格取文本等「Excel 单元格 → 值」的逻辑，已随批次 13 · 13.3
     * 搬到 {@link ExcelCellParser}（它不碰数据库、也不含业务规则，是内聚的一小块）。
     */

}
