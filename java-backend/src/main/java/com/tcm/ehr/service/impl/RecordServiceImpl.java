package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RecordUtil;
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
import com.tcm.ehr.service.IDictionaryFileService;
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
 * 病历数据服务实现：Excel 批量导入 + 单条新增 + 导入进度（内存）。
 *
 * <ul>
 * <li>解析：POI {@link WorkbookFactory}（兼容 .xlsx / .xls）；表头中文名 → 21 字段；</li>
 * <li>去重：复用 {@link RecordUtil#textHash}（21 字段固定顺序 MD5），与数据清洗同口径；</li>
 * <li>进度：内存 Map（taskId → 状态），服务重启后丢失，查询返回 404（与 openapi 一致）；</li>
 * <li>导入同步执行：接口返回即本轮完成，status 直接为「已完成」。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordServiceImpl extends ServiceImpl<RecordMapper, Record> implements IRecordService {

    private final ObjectMapper objectMapper;
    private final IDictionaryFileService dictionaryFileService;
    private final com.tcm.ehr.service.INlpBatchService nlpBatchService;
    private final com.tcm.ehr.mapper.ReviewTaskMapper reviewTaskMapper;

    /** 原始 21 字段（禁止通过修改接口变更，命中即 400 code=1007） */
    private static final Set<String> ORIGINAL_FIELDS = Set.of(
            "registrationNo", "outpatientNo", "gender", "age", "visitCount",
            "westernDiagnosis", "tcmDiagnosis", "presentIllness", "chiefComplaint", "selfReport",
            "inspection", "pulse", "tongue", "physicalExam", "pattern", "prescription",
            "followUp", "treatmentEffect", "department", "doctorId", "visitTime");

    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILES = 20;
    /** 删除分块大小（先删 review_tasks 再删 records，避免一次 IN 过大） */
    private static final int DELETE_CHUNK = 500;

    /** 表头中文名 → 字段标识（与 docs/电子病历精简脱敏数据_500行.xlsx 的表头一致） */
    // P5.4：构建期填充，完成后 unmodifiable（同 EntityTypes）
    private static final Map<String, String> HEADER_FIELD;

    static {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("登记号", "registrationNo");
        header.put("门诊号", "outpatientNo");
        header.put("性别", "gender");
        header.put("年龄", "age");
        header.put("就诊次数", "visitCount");
        header.put("西医诊断", "westernDiagnosis");
        header.put("中医诊断", "tcmDiagnosis");
        header.put("现病史", "presentIllness");
        header.put("主诉", "chiefComplaint");
        header.put("自诉", "selfReport");
        header.put("望诊", "inspection");
        header.put("脉诊", "pulse");
        header.put("舌诊", "tongue");
        header.put("查体", "physicalExam");
        header.put("辨证结论", "pattern");
        header.put("证型", "pattern"); // 兼容旧表头
        header.put("草药", "prescription");
        header.put("随访", "followUp");
        header.put("治疗效果", "treatmentEffect");
        header.put("开单科室", "department");
        header.put("医生工号", "doctorId");
        header.put("接诊时间", "visitTime");
        HEADER_FIELD = java.util.Collections.unmodifiableMap(header);
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 「接诊时间」列里的纯数字紧凑串：8 位到日 / 12 位到分 / 14 位到秒 */
    private static final Pattern COMPACT_DT = Pattern.compile("\\d{8}|\\d{12}|\\d{14}");

    /** 导入告警里最多列几条「接诊时间」解析失败样例；全列出来会把日志刷爆 */
    private static final int VISIT_TIME_WARN_SAMPLE_MAX = 10;


    /**
     * Excel 批量导入病历（同步执行，返回即本轮完成）。
     *
     * <p>逐文件校验扩展名、大小（≤50MB）与必需列「登记号」「接诊时间」，逐行映射为 21 字段；
     * 缺列、解析失败、门诊号为空的行计入失败明细，不影响其余行。去重先按登记号预取库内病历哈希、
     * 再与本批内哈希比对（与数据清洗同口径的 21 字段文本哈希），命中的按重复记失败。落库走
     * {@code saveBatch} 一次批量插入，结果同时写入内存任务表（供进度查询，重启即失）。开启
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
            parseFile(file, summary, parsedRows, batchRegNos, visitTimeWarn, visitTimeWarnSamples);
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
                            .eq("group_id", RequestUtils.currentGroupId()));
            for (Record r : existing) {
                existingHash.add(RecordUtil.textHash(r));
            }
        }

        // 6. 逐行去重并收进待插入列表（P3.4 拆出 dedupeAndCollect）
        dedupeAndCollect(parsedRows, existingHash, summary, toInsert);

        // 7. 批量落库并回填成功数
        if (!toInsert.isEmpty()) {
            saveBatch(toInsert);
        }
        summary.setSuccess(toInsert.size());

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

    /**
     * 解析单个上传文件（P3.4 从 importRecords 抽出）：文件级校验 → 表头校验 → 逐行映射。
     *
     * <p>文件级问题（空 / 超限 / 后缀不符 / 缺表头 / 缺必需列）记失败并返回；
     * 行级问题记失败明细；成功的行列进 {@code parsedRows} 并累积待查重登记号。
     * 「接诊时间」有值却解析不出的行照旧入库，仅计数留痕（见调用侧 warn）。</p>
     *
     * @param visitTimeWarn 长度 1 的计数容器（跨文件累加）
     */
    private void parseFile(MultipartFile file, ImportSummaryVO summary, List<Object[]> parsedRows,
                           Set<String> batchRegNos, int[] visitTimeWarn, List<String> visitTimeWarnSamples) {
        String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
        if (file.isEmpty()) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "文件为空"));
            return;
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "单文件超过 50MB"));
            return;
        }
        String lower = filename.toLowerCase();
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "仅支持 .xlsx / .xls"));
            return;
        }
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少表头"));
                return;
            }
            Map<String, Integer> colIndex = buildHeaderIndex(header);
            if (!colIndex.containsKey("registrationNo")) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「登记号」"));
                return;
            }
            if (!colIndex.containsKey("visitTime")) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「接诊时间」"));
                return;
            }
            // 逐行映射：登记号为空的行跳过，缺门诊号或映射失败记入失败明细
            for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || TextUtil.isBlank(cellText(row.getCell(colIndex.getOrDefault("registrationNo", -1))))) {
                    continue;
                }
                summary.setTotal(summary.getTotal() + 1);
                try {
                    Record r = mapRow(row, colIndex);
                    if (TextUtil.isBlank(r.getOutpatientNo())) {
                        throw new IllegalArgumentException("门诊号为空");
                    }
                    batchRegNos.add(r.getRegistrationNo());
                    parsedRows.add(new Object[]{r, filename});
                    // 「接诊时间」有原值却解析不出来：该行照旧入库，但要计数留痕。
                    // 该列是必需列，静默按 null 入库会让列表接诊时间列、就诊月份趋势、
                    // 日期范围筛选、去重哈希同时悄悄退化（2026-09-28 的实际故障）
                    String rawVisit = cellText(row.getCell(colIndex.get("visitTime")));
                    if (r.getVisitTime() == null && !TextUtil.isBlank(rawVisit)) {
                        visitTimeWarn[0]++;
                        if (visitTimeWarnSamples.size() < VISIT_TIME_WARN_SAMPLE_MAX) {
                            visitTimeWarnSamples.add("第 " + (i + 1) + " 行「" + rawVisit + "」");
                        }
                    }
                } catch (Exception e) {
                    summary.setFailed(summary.getFailed() + 1);
                    summary.getFailures().add(new ImportSummaryVO.Failure(filename,
                            "第 " + (i + 1) + " 行：" + e.getMessage()));
                }
            }
        } catch (Exception e) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "解析失败：" + e.getMessage()));
        }
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
            r.setGroupId(RequestUtils.currentGroupId());
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
            throw new IllegalArgumentException("登记号不能为空");
        }
        // 2. 必填校验：门诊号
        if (TextUtil.isBlank(dto.getOutpatientNo())) {
            throw new IllegalArgumentException("门诊号不能为空");
        }
        // 3. 组装病历实体：主键由服务端生成，21 个原始字段原样落库；入本组
        Record r = new Record();
        r.setId(UUID.randomUUID().toString());
        r.setGroupId(RequestUtils.currentGroupId());
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
        // 原始 21 字段只读：显式携带原始字段即拒绝（code=1007 语义）
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
        // 4. 打上当前词典版本戳后落库
        json = StructuredDataMeta.stamp(objectMapper, json, dictionaryFileService.currentVersion());
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
        // 1. 没选 id 直接拒：空删会静默"成功"，用户以为删掉了
        if (dto == null || dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new IllegalArgumentException("未选择要操作的病历");
        }
        // 2. 交给同一段删除逻辑（事务已加在本方法上）
        return doDelete(dto.getIds());
    }

    /**
     * 按筛选范围删除。
     *
     * <p>事务横跨「按范围取 id + 分块删」，保证 {@code review_tasks} 与 {@code records} 一起回滚。</p>
     *
     * <p>ponytail: 整批一个事务，3.5 万条时锁范围偏大、时长也长；当前演示库 500 条无感。
     * 真到全库量级，应改成「先算 id、再分批各自提交」，并配合单次删除上限。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public DeleteRecordsVO deleteByFilter(FiltersDTO filters) {
        // 1. 必须至少有一个筛选条件，否则就是「删全库」
        if (!hasAnyFilter(filters)) {
            throw new IllegalArgumentException("请至少设置一个筛选条件，避免误删全库");
        }
        // 2. 只取 id 列，不取整行数据
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentGroupId(), filters);
        List<Record> rows = baseMapper.selectList(wrapper.select("id"));
        List<String> ids = rows.stream().map(Record::getId).toList();
        // 3. 走同一段删除逻辑
        return doDelete(ids);
    }

    /**
     * 实际删除：先清外键依赖（review_tasks.record_id → records.id），再分块删病历，避免外键约束报错。
     */
    private DeleteRecordsVO doDelete(List<String> ids) {
        DeleteRecordsVO vo = new DeleteRecordsVO();
        // 1. 空集合按删 0 条返回
        if (ids == null || ids.isEmpty()) {
            vo.setDeletedCount(0);
            return vo;
        }
        // 2. 分块删：一条 IN 塞几万个 id 会让 SQL 慢到超时
        int deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_CHUNK) {
            List<String> chunk = ids.subList(i, Math.min(i + DELETE_CHUNK, ids.size()));
            // 3. 顺序不能反：先删子表再删主表，反了会撞外键
            reviewTaskMapper.delete(new QueryWrapper<com.tcm.ehr.domain.po.ReviewTask>().in("record_id", chunk));
            deleted += baseMapper.deleteBatchIds(chunk);
        }
        vo.setDeletedCount(deleted);
        return vo;
    }

    /** 范围条件是否至少有一个（部门/证候/分级任一非空，或时间区间两端齐全） */
    private boolean hasAnyFilter(FiltersDTO f) {
        // 1. 没有条件对象就没有任何筛选
        if (f == null) {
            return false;
        }
        // 2. 时间区间要两端都有才算一个条件，缺一端会变成"从某时到最新"这种误删口径
        boolean range = f.getDateRange() != null && f.getDateRange().size() == 2
                && !TextUtil.isBlank(f.getDateRange().get(0)) && !TextUtil.isBlank(f.getDateRange().get(1));
        // 3. 任一维度非空即算有筛选
        return !TextUtil.isBlank(f.getDepartment()) || !TextUtil.isBlank(f.getPattern()) || !TextUtil.isBlank(f.getGrade()) || range;
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
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentGroupId(), dto);
        // 2. 分页查询（条件已含数据域与用户筛选）
        Page<Record> p = baseMapper.selectPage(new Page<>(page, size), wrapper);
        // 3. 组装返回：总数与当前页列表项
        SearchVO vo = new SearchVO();
        vo.setTotal(p.getTotal());
        for (Record r : p.getRecords()) {
            vo.getRecords().add(new SearchVO.Item(r.getId(), summarize(r), r.getGrade(),
                    r.getVisitTime(), r.getGender(), r.getAge()));
        }
        return vo;
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

    /** 表头行 → 字段标识:列索引 */
    private Map<String, Integer> buildHeaderIndex(Row header) {
        Map<String, Integer> idx = new HashMap<>();
        // 1. 逐列取表头文本，空列跳过
        for (Cell cell : header) {
            String text = cellText(cell);
            if (TextUtil.isBlank(text)) {
                continue;
            }
            String field = HEADER_FIELD.get(text.trim());
            // 2. 只认能映射的列；同名字段取第一次出现的列，避免后面重复表头覆盖它
            if (field != null && !idx.containsKey(field)) {
                idx.put(field, cell.getColumnIndex());
            }
        }
        return idx;
    }

    /** 数据行 → 病历实体：按表头索引逐字段取值，缺列一律 null */
    private Record mapRow(Row row, Map<String, Integer> idx) {
        Record r = new Record();
        // 1. 文本列按表头索引逐字段取，缺列由 get() 兜成 null
        r.setRegistrationNo(get(row, idx, "registrationNo"));
        r.setOutpatientNo(get(row, idx, "outpatientNo"));
        r.setGender(get(row, idx, "gender"));
        r.setAge(get(row, idx, "age"));
        r.setVisitCount(parseInt(get(row, idx, "visitCount")));
        r.setWesternDiagnosis(get(row, idx, "westernDiagnosis"));
        r.setTcmDiagnosis(get(row, idx, "tcmDiagnosis"));
        r.setPresentIllness(get(row, idx, "presentIllness"));
        r.setChiefComplaint(get(row, idx, "chiefComplaint"));
        r.setSelfReport(get(row, idx, "selfReport"));
        r.setInspection(get(row, idx, "inspection"));
        r.setPulse(get(row, idx, "pulse"));
        r.setTongue(get(row, idx, "tongue"));
        r.setPhysicalExam(get(row, idx, "physicalExam"));
        r.setPattern(get(row, idx, "pattern"));
        r.setPrescription(get(row, idx, "prescription"));
        r.setFollowUp(get(row, idx, "followUp"));
        r.setTreatmentEffect(get(row, idx, "treatmentEffect"));
        r.setDepartment(get(row, idx, "department"));
        r.setDoctorId(get(row, idx, "doctorId"));
        // 缺列时返回 null：不能写 getCell(idx.getOrDefault("visitTime", -1))，
        // 那样 getCell(-1) 会抛 IllegalArgumentException，整行都被记成解析失败
        Integer visitIdx = idx.get("visitTime");
        r.setVisitTime(visitIdx == null ? null : parseDateTime(row.getCell(visitIdx)));
        return r;
    }

    /** 按字段标识取单元格文本；该列在表头里不存在时返回 null */
    private String get(Row row, Map<String, Integer> idx, String field) {
        // 1. 表头里没这列就返回 null，调用侧不必判存在性
        Integer c = idx.get(field);
        return c == null ? null : cellText(row.getCell(c));
    }

    private Integer parseInt(String s) {
        // 1. 空值直接给 null
        if (TextUtil.isBlank(s)) {
            return null;
        }
        // 2. 按 double 解析：Excel 数值列读出来常带 ".0"；解析不了给 null，不让整行失败
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 接诊时间：支持 Excel 日期数值、紧凑数字串与常见字符串格式；认不出来给 null */
    private LocalDateTime parseDateTime(Cell cell) {
        // 1. 空单元格给 null
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        // 2. Excel 真正的日期型单元格直接取值，避开时区与格式转换
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        // 3. 其余按文本处理
        String s = cellText(cell);
        if (TextUtil.isBlank(s)) {
            return null;
        }
        try {
            // 4. 先归一为标准格式，再统一解析
            return LocalDateTime.parse(normalizeDateTime(s), DT);
        } catch (Exception e) {
            // 5. 格式不认识给 null：宁可缺接诊时间，也不要让整行导入失败
            return null;
        }
    }

    /**
     * 把「接诊时间」单元格文本归一为 {@code yyyy-MM-dd HH:mm:ss}。
     *
     * <p>导入源这一列是 14 位紧凑数字串（{@code 20221224090613}），单元格类型为数值、
     * 格式为 General —— 既不是日期格式、也不含分隔符。只按 {@code yyyy-MM-dd HH:mm:ss}
     * 硬解析会全部落到 null，表现为列表「接诊时间」整列空白。这里统一收口五类写法：</p>
     *
     * <ul>
     * <li>纯数字紧凑串 8 / 12 / 14 位：{@code 20221224} / {@code 202212240906} / {@code 20221224090613}</li>
     * <li>中文年月日：{@code 2022年12月24日}</li>
     * <li>斜杠与点分隔：{@code 2022/12/24} / {@code 2022.12.24}</li>
     * <li>缺省部分：只到日补 {@code 00:00:00}，只到分补 {@code :00}</li>
     * <li>多余部分：ISO 的 {@code T} 换成空格，小数秒与时区后缀截掉</li>
     * </ul>
     *
     * <p>认不出来的原样返回，由调用侧的 {@code LocalDateTime.parse} 抛错并落到 null。</p>
     */
    static String normalizeDateTime(String raw) {
        // 1. 去首尾空白、ISO 的 T 换空格、连续空白压成一个
        String s = raw.trim().replace('T', ' ').replaceAll("\\s+", " ");
        // 2. 纯数字紧凑串：8 位到日 / 12 位到分 / 14 位到秒
        if (COMPACT_DT.matcher(s).matches()) {
            String date = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
            String hourMinute = s.length() >= 12 ? s.substring(8, 10) + ":" + s.substring(10, 12) : "00:00";
            String second = s.length() == 14 ? s.substring(12, 14) : "00";
            return date + " " + hourMinute + ":" + second;
        }
        // 3. 以第一个空格拆日期段与时间段
        int sp = s.indexOf(' ');
        String date = sp < 0 ? s : s.substring(0, sp);
        String time = sp < 0 ? "" : s.substring(sp + 1);
        // 4. 日期段：中文年月日与斜杠点都换成短横，再把月日补成两位
        date = padDate(date.replace("年", "-").replace("月", "-").replace("日", "")
                .replace('/', '-').replace('.', '-'));
        // 5. 时间段：按冒号拆成 时:分:秒，逐段补零、缺段补 00，多余部分（小数秒/时区）截掉
        String[] t = time.isEmpty() ? new String[0] : time.split(":");
        String hh = t.length > 0 ? pad2(t[0]) : "00";
        String mm = t.length > 1 ? pad2(t[1]) : "00";
        String ss = t.length > 2 ? pad2(t[2].length() > 2 ? t[2].substring(0, 2) : t[2]) : "00";
        return date + " " + hh + ":" + mm + ":" + ss;
    }

    /** 日期段补零：{@code 2022-1-2} → {@code 2022-01-02}；不是三段或年份不足四位则原样返回 */
    private static String padDate(String date) {
        String[] p = date.split("-");
        if (p.length != 3 || p[0].length() != 4) {
            return date;
        }
        return p[0] + "-" + pad2(p[1]) + "-" + pad2(p[2]);
    }

    /** 一位数补成两位，其余原样；非数字留给后续 parse 抛错兜住 */
    private static String pad2(String v) {
        return v.length() == 1 ? "0" + v : v;
    }

    /** 单元格取文本：按显示格式取值，数字/日期型统一转字符串 */
    private String cellText(Cell cell) {
        // 1. 空单元格给 null
        if (cell == null) {
            return null;
        }
        // 2. 按单元格类型取值
        return switch (cell.getCellType()) {
            case STRING -> {
                String v = cell.getStringCellValue();
                yield v == null || v.isBlank() ? null : v.trim();
            }
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }
}
