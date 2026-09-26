package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import tools.jackson.databind.ObjectMapper;
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
import com.tcm.ehr.domain.vo.ImportStatusVO;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 病历数据服务实现（批F·7.1）：Excel 批量导入 + 单条新增 + 导入进度（内存）。
 *
 * <ul>
 *   <li>解析：POI {@link WorkbookFactory}（兼容 .xlsx / .xls）；表头中文名 → 21 字段；</li>
 *   <li>去重：复用 {@link RecordUtil#textHash}（21 字段固定顺序 MD5），与数据清洗同口径；</li>
 *   <li>进度：内存 Map（taskId → 状态），服务重启后丢失，查询返回 404（与 openapi 一致）；</li>
 *   <li>导入同步执行：接口返回即本轮完成，status 直接为「已完成」。</li>
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

    /** 表头中文名 → 字段标识（与 data/seed-database.py 的 Excel 表头一致） */
    private static final Map<String, String> HEADER_FIELD = new LinkedHashMap<>();

    static {
        HEADER_FIELD.put("登记号", "registrationNo");
        HEADER_FIELD.put("门诊号", "outpatientNo");
        HEADER_FIELD.put("性别", "gender");
        HEADER_FIELD.put("年龄", "age");
        HEADER_FIELD.put("就诊次数", "visitCount");
        HEADER_FIELD.put("西医诊断", "westernDiagnosis");
        HEADER_FIELD.put("中医诊断", "tcmDiagnosis");
        HEADER_FIELD.put("现病史", "presentIllness");
        HEADER_FIELD.put("主诉", "chiefComplaint");
        HEADER_FIELD.put("自诉", "selfReport");
        HEADER_FIELD.put("望诊", "inspection");
        HEADER_FIELD.put("脉诊", "pulse");
        HEADER_FIELD.put("舌诊", "tongue");
        HEADER_FIELD.put("查体", "physicalExam");
        HEADER_FIELD.put("辨证结论", "pattern");
        HEADER_FIELD.put("证型", "pattern"); // 兼容旧表头
        HEADER_FIELD.put("草药", "prescription");
        HEADER_FIELD.put("随访", "followUp");
        HEADER_FIELD.put("治疗效果", "treatmentEffect");
        HEADER_FIELD.put("开单科室", "department");
        HEADER_FIELD.put("医生工号", "doctorId");
        HEADER_FIELD.put("接诊时间", "visitTime");
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 内存里最多保留多少条导入任务状态；超出淘汰最早的 */
    private static final int TASK_STORE_MAX = 200;

    /** 导入任务状态（内存；重启丢失，符合 openapi 约定） */
    private final Map<String, ImportStatusVO> taskStore = newTaskStore(TASK_STORE_MAX);

    /**
     * 有界任务表：超过上限就淘汰最早插入的那条。
     *
     * <p>原来是无上限的 {@code ConcurrentHashMap} 且全文件没有 {@code remove} ——
     * 每次导入新增一条（还带失败明细），长期运行内存持续增长（审查报告 M5）。</p>
     *
     * <p>用 {@code LinkedHashMap.removeEldestEntry} 做有界是零依赖的做法（仓库里没有 Caffeine）；
     * 外面套 {@code synchronizedMap} 保持线程安全 —— 导入与查进度是两个请求线程。</p>
     *
     * <p>不做 TTL：任务状态是给导入后回看用的，有界即可，不值得为它加依赖或定时任务。</p>
     */
    static Map<String, ImportStatusVO> newTaskStore(int maxEntries) {
        // 匿名内部类 + 菱形推断会退化成 LinkedHashMap<Object,Object>，这里显式写泛型
        return Collections.synchronizedMap(new LinkedHashMap<String, ImportStatusVO>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ImportStatusVO> eldest) {
                return size() > maxEntries;
            }
        });
    }

    @Override
    public ImportTaskVO importRecords(MultipartFile[] files, boolean autoExtract) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("请上传至少一个文件");
        }
        if (files.length > MAX_FILES) {
            throw new IllegalArgumentException("单次最多上传 " + MAX_FILES + " 个文件");
        }

        String taskId = UUID.randomUUID().toString();
        ImportStatusVO status = new ImportStatusVO();
        status.setTaskId(taskId);
        status.setStatus("处理中");
        taskStore.put(taskId, status);

        ImportSummaryVO summary = new ImportSummaryVO();
        List<Record> toInsert = new ArrayList<>();
        Set<String> seenHash = new HashSet<>();
        Map<String, String> regNoSource = new HashMap<>();

        // 预取本批登记号对应的库内病历哈希，用于跨批去重（不必全表扫描）
        Set<String> batchRegNos = new HashSet<>();

        List<Object[]> parsedRows = new ArrayList<>(); // [Record, filename]

        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename();
            if (file.isEmpty()) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "文件为空"));
                continue;
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "单文件超过 50MB"));
                continue;
            }
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
                summary.setFailed(summary.getFailed() + 1);
                summary.getFailures().add(new ImportSummaryVO.Failure(filename, "仅支持 .xlsx / .xls"));
                continue;
            }
            try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
                Sheet sheet = wb.getSheetAt(0);
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (header == null) {
                    summary.setFailed(summary.getFailed() + 1);
                    summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少表头"));
                    continue;
                }
                Map<String, Integer> colIndex = buildHeaderIndex(header);
                if (!colIndex.containsKey("registrationNo")) {
                    summary.setFailed(summary.getFailed() + 1);
                    summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「登记号」"));
                    continue;
                }
                if (!colIndex.containsKey("visitTime")) {
                    summary.setFailed(summary.getFailed() + 1);
                    summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「接诊时间」"));
                    continue;
                }
                for (int i = header.getRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null || isBlank(cellText(row.getCell(colIndex.getOrDefault("registrationNo", -1))))) {
                        continue;
                    }
                    summary.setTotal(summary.getTotal() + 1);
                    try {
                        Record r = mapRow(row, colIndex);
                        if (isBlank(r.getOutpatientNo())) {
                            throw new IllegalArgumentException("门诊号为空");
                        }
                        batchRegNos.add(r.getRegistrationNo());
                        parsedRows.add(new Object[]{r, filename});
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

        // 库内已存在哈希（按登记号预筛，避免全表扫描）
        Set<String> existingHash = new HashSet<>();
        if (!batchRegNos.isEmpty()) {
            List<Record> existing = baseMapper.selectList(
                    new QueryWrapper<Record>().in("registration_no", batchRegNos));
            for (Record r : existing) {
                existingHash.add(RecordUtil.textHash(r));
            }
        }

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
            toInsert.add(r);
        }

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

        status.setStatus("已完成");
        status.setTotal(summary.getTotal());
        status.setProcessed(summary.getTotal());
        status.setSuccess(summary.getSuccess());
        status.setFailed(summary.getFailed());
        status.setFailures(summary.getFailures());

        ImportTaskVO vo = new ImportTaskVO();
        vo.setTaskId(taskId);
        vo.setSummary(summary);
        vo.setAutoExtractTaskId(autoTaskId);
        log.info("[病历导入] task={} 文件={} 行={} 成功={} 失败={}",
                taskId, files.length, summary.getTotal(), summary.getSuccess(), summary.getFailed());
        return vo;
    }

    @Override
    public ImportStatusVO importStatus(String taskId) {
        return taskStore.get(taskId);
    }

    @Override
    public CreateRecordVO createRecord(CreateRecordDTO dto) {
        if (dto == null || isBlank(dto.getRegistrationNo())) {
            throw new IllegalArgumentException("登记号不能为空");
        }
        if (isBlank(dto.getOutpatientNo())) {
            throw new IllegalArgumentException("门诊号不能为空");
        }
        Record r = new Record();
        r.setId(UUID.randomUUID().toString());
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
        baseMapper.insert(r);

        CreateRecordVO vo = new CreateRecordVO();
        vo.setId(r.getId());
        return vo;
    }

    @Override
    public RawRecordVO getRawRecord(String recordId) {
        Record r = baseMapper.selectById(recordId);
        if (r == null) {
            return null;
        }
        // 数据域（行级权限）：审核员仅可见待复核病历
        if (RecordFilter.ROLE_AUDITOR.equals(RequestUtils.currentRole())
                && !"待复核".equals(r.getGrade())) {
            throw new ForbiddenException("无权查看非待复核病历");
        }
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

    @Override
    public void updateRecord(String recordId, Map<String, Object> body) {
        Record r = baseMapper.selectById(recordId);
        if (r == null) {
            throw new IllegalArgumentException("病历不存在");
        }
        // 原始 21 字段只读：显式携带原始字段即拒绝（code=1007 语义）
        if (body != null) {
            for (String key : body.keySet()) {
                if (ORIGINAL_FIELDS.contains(key)) {
                    throw new IllegalArgumentException("原始字段不允许修改");
                }
            }
        }
        Object sd = body == null ? null : body.get("structuredData");
        if (sd == null) {
            throw new IllegalArgumentException("未提供结构化数据");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(sd);
        } catch (Exception e) {
            throw new IllegalArgumentException("structuredData 格式错误");
        }
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
        if (dto == null || dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new IllegalArgumentException("未选择要操作的病历");
        }
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
        if (!hasAnyFilter(filters)) {
            throw new IllegalArgumentException("请至少设置一个筛选条件，避免误删全库");
        }
        QueryWrapper<Record> wrapper = RecordFilter.build(RecordFilter.ROLE_ADMIN, filters);
        List<Record> rows = baseMapper.selectList(wrapper.select("id"));
        List<String> ids = rows.stream().map(Record::getId).toList();
        return doDelete(ids);
    }

    /**
     * 实际删除：先清外键依赖（review_tasks.record_id → records.id），再分块删病历，避免外键约束报错。
     */
    private DeleteRecordsVO doDelete(List<String> ids) {
        DeleteRecordsVO vo = new DeleteRecordsVO();
        if (ids == null || ids.isEmpty()) {
            vo.setDeletedCount(0);
            return vo;
        }
        int deleted = 0;
        for (int i = 0; i < ids.size(); i += DELETE_CHUNK) {
            List<String> chunk = ids.subList(i, Math.min(i + DELETE_CHUNK, ids.size()));
            reviewTaskMapper.delete(new QueryWrapper<com.tcm.ehr.domain.po.ReviewTask>().in("record_id", chunk));
            deleted += baseMapper.deleteBatchIds(chunk);
        }
        vo.setDeletedCount(deleted);
        return vo;
    }

    /** 范围条件是否至少有一个（部门/证候/分级任一非空，或时间区间两端齐全） */
    private boolean hasAnyFilter(FiltersDTO f) {
        if (f == null) {
            return false;
        }
        boolean range = f.getDateRange() != null && f.getDateRange().size() == 2
                && !isBlank(f.getDateRange().get(0)) && !isBlank(f.getDateRange().get(1));
        return !isBlank(f.getDepartment()) || !isBlank(f.getPattern()) || !isBlank(f.getGrade()) || range;
    }

    @Override
    public SearchVO searchRecords(SearchDTO dto) {
        int page = dto != null && dto.getPage() != null && dto.getPage() > 0 ? dto.getPage() : 1;
        int size = dto != null && dto.getPageSize() != null && dto.getPageSize() > 0 ? dto.getPageSize() : 20;
        // 数据域 → 用户筛选，取交集（统一走 RecordFilter，禁止手写 where）
        QueryWrapper<Record> wrapper = RecordFilter.build(RequestUtils.currentRole(), dto);
        Page<Record> p = baseMapper.selectPage(new Page<>(page, size), wrapper);
        SearchVO vo = new SearchVO();
        vo.setTotal(p.getTotal());
        for (Record r : p.getRecords()) {
            vo.getRecords().add(new SearchVO.Item(r.getId(), summarize(r), r.getGrade(),
                    r.getVisitTime(), r.getGender(), r.getAge()));
        }
        return vo;
    }

    private String summarize(Record r) {
        String s = r.getChiefComplaint();
        if (isBlank(s)) {
            s = r.getTcmDiagnosis();
        }
        if (isBlank(s)) {
            s = r.getWesternDiagnosis();
        }
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // ============ 解析辅助 ============

    /** 表头行 → 字段标识:列索引 */
    private Map<String, Integer> buildHeaderIndex(Row header) {
        Map<String, Integer> idx = new HashMap<>();
        for (Cell cell : header) {
            String text = cellText(cell);
            if (isBlank(text)) {
                continue;
            }
            String field = HEADER_FIELD.get(text.trim());
            if (field != null && !idx.containsKey(field)) {
                idx.put(field, cell.getColumnIndex());
            }
        }
        return idx;
    }

    private Record mapRow(Row row, Map<String, Integer> idx) {
        Record r = new Record();
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
        // 与同文件 get() 一致的写法：缺列时返回 null。
        // 原写法 getCell(idx.getOrDefault("visitTime", -1)) 在缺列时 getCell(-1) 会抛
        // IllegalArgumentException，被上层记成 POI 的「Cell index must be >= 0」，每行都失败。
        Integer visitIdx = idx.get("visitTime");
        r.setVisitTime(visitIdx == null ? null : parseDateTime(row.getCell(visitIdx)));
        return r;
    }

    private String get(Row row, Map<String, Integer> idx, String field) {
        Integer c = idx.get(field);
        return c == null ? null : cellText(row.getCell(c));
    }

    private Integer parseInt(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 接诊时间：支持 Excel 日期数值与常见字符串格式 */
    private LocalDateTime parseDateTime(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        String s = cellText(cell);
        if (isBlank(s)) {
            return null;
        }
        s = s.trim().replace('T', ' ');
        try {
            if (s.length() == 10) {
                return LocalDateTime.parse(s + " 00:00:00", DT);
            }
            if (s.length() >= 19) {
                return LocalDateTime.parse(s.substring(0, 19), DT);
            }
            return LocalDateTime.parse(s, DT);
        } catch (Exception e) {
            return null;
        }
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
