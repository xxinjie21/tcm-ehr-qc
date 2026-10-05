package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.ExcelRawStreamReader;
import com.tcm.ehr.common.utils.TextUtil;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ImportSummaryVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Excel 行/表头 → 病历实体的解析（批次 13 · 13.3 的第五个抽取对象）。
 *
 * <p>这一块是导入流程里「读表」的部分：建表头索引、校验必需列、逐行映射并记账、流式读 xlsx。
 * 它不含任何数据库或业务决策 —— 谁入库、入哪个组、怎么去重，仍由
 * {@code RecordServiceImpl} 决定。</p>
 *
 * <p>两条读法（POI 全量读 .xls/.xlsx 与 SAX 流式读 .xlsx）**共用**这里的列映射与记账逻辑，
 * 这是原代码刻意的设计，搬过来时一并保留：若各写一份，迟早出现「xlsx 导入少一列、xls 正常」
 * 这类只在某种格式下复现的问题。</p>
 */
final class ExcelSheetImporter {

    /** 接诊时间告警的样例上限（只影响提示里列几行，不影响计数） */
    static final int VISIT_TIME_WARN_SAMPLE_MAX = 10;

    private ExcelSheetImporter() {
    }

    /** 表头行 → 字段标识:列索引 */
    static Map<String, Integer> buildHeaderIndex(Row header) {
        Map<String, Integer> idx = new HashMap<>();
        // 1. 逐列取表头文本，空列跳过
        for (Cell cell : header) {
            String text = ExcelCellParser.cellText(cell);
            if (TextUtil.isBlank(text)) {
                continue;
            }
            String field = ExcelHeaderFields.MAP.get(text.trim());
            // 2. 只认能映射的列；同名字段取第一次出现的列，避免后面重复表头覆盖它
            if (field != null && !idx.containsKey(field)) {
                idx.put(field, cell.getColumnIndex());
            }
        }
        return idx;
    }

    /** 流式读 .xlsx：表头为首行，逐行回调直接走同一套映射 */
    static void parseXlsxStreaming(MultipartFile file, String filename, ImportSummaryVO summary,
                                   Set<String> batchRegNos, List<Object[]> parsedRows,
                                   int[] visitTimeWarn, List<String> visitTimeWarnSamples) throws IOException {
        Map<String, Integer>[] colIndex = new Map[]{null};
        // 表头校验失败要中止整份文件：用异常跳出 SAX 回调，下面就地接住
        IllegalStateException[] abort = new IllegalStateException[1];
        ExcelRawStreamReader.forEachXlsxRow(file.getInputStream(), (rowNum, cells) -> {
            if (abort[0] != null) {
                return;
            }
            if (rowNum == 0) {
                Map<String, Integer> idx = new HashMap<>();
                for (ExcelRawStreamReader.RawCell c : cells) {
                    String t = c.text();
                    if (TextUtil.isBlank(t)) {
                        continue;
                    }
                    String field = ExcelHeaderFields.MAP.get(t.trim());
                    if (field != null && !idx.containsKey(field)) {
                        idx.put(field, c.col());
                    }
                }
                if (idx.isEmpty()) {
                    summary.setFailed(summary.getFailed() + 1);
                    summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少表头"));
                    abort[0] = new IllegalStateException("abort");
                    return;
                }
                if (!checkRequiredColumns(idx, summary, filename)) {
                    abort[0] = new IllegalStateException("abort");
                    return;
                }
                colIndex[0] = idx;
                return;
            }
            if (colIndex[0] == null) {
                return;
            }
            // 与 POI 路径共用同一段逐行处理
            processRow(ExcelRowReader.of(cells), rowNum, colIndex[0], filename, summary, batchRegNos,
                    parsedRows, visitTimeWarn, visitTimeWarnSamples);
        });
    }

    /** 必需列校验（两条路径共用，失败文案只此一份） */
    static boolean checkRequiredColumns(Map<String, Integer> colIndex, ImportSummaryVO summary, String filename) {
        if (!colIndex.containsKey("registrationNo")) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「登记号」"));
            return false;
        }
        if (!colIndex.containsKey("visitTime")) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename, "缺少必需列「接诊时间」"));
            return false;
        }
        return true;
    }

    /** 逐行映射与记账（POI 与流式两条路径共用） */
    static void processRow(ExcelRowReader.RowAccess row, int rowNum, Map<String, Integer> colIndex, String filename,
                           ImportSummaryVO summary, Set<String> batchRegNos, List<Object[]> parsedRows,
                           int[] visitTimeWarn, List<String> visitTimeWarnSamples) {
        if (TextUtil.isBlank(row.text(colIndex.getOrDefault("registrationNo", -1)))) {
            return;
        }
        summary.setTotal(summary.getTotal() + 1);
        try {
            Record r = mapRow(row, colIndex);
            if (TextUtil.isBlank(r.getOutpatientNo())) {
                throw new IllegalArgumentException("门诊号为空");
            }
            batchRegNos.add(r.getRegistrationNo());
            parsedRows.add(new Object[]{r, filename});
            // 「接诊时间」有原值却解析不出来：该行照旧入库，但要计数留痕
            String rawVisit = row.text(colIndex.get("visitTime"));
            if (r.getVisitTime() == null && !TextUtil.isBlank(rawVisit)) {
                visitTimeWarn[0]++;
                if (visitTimeWarnSamples.size() < VISIT_TIME_WARN_SAMPLE_MAX) {
                    visitTimeWarnSamples.add("第 " + (rowNum + 1) + " 行「" + rawVisit + "」");
                }
            }
        } catch (Exception e) {
            summary.setFailed(summary.getFailed() + 1);
            summary.getFailures().add(new ImportSummaryVO.Failure(filename,
                    "第 " + (rowNum + 1) + " 行：" + e.getMessage()));
        }
    }

    /** 数据行 → 病历实体：按表头索引逐字段取值，缺列一律 null */
    static Record mapRow(ExcelRowReader.RowAccess row, Map<String, Integer> idx) {
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
        r.setVisitTime(visitIdx == null ? null : row.dateTime(visitIdx));
        // 导入与单条新增同口径：新入库一律 pending（还没跑质控），别留空
        r.setStatus("pending");
        return r;
    }

    /** 按字段标识取单元格文本；该列在表头里不存在时返回 null */
    static String get(ExcelRowReader.RowAccess row, Map<String, Integer> idx, String field) {
        // 1. 表头里没这列就返回 null，调用侧不必判存在性
        Integer c = idx.get(field);
        return c == null ? null : row.text(c);
    }

    static Integer parseInt(String s) {
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
}
