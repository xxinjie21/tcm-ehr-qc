package com.tcm.ehr.service.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 导入 Excel 的表头中文名 → 字段标识（批次 13 · 13.3 抽取的第一个子块）。
 *
 * <p>与 {@code docs/电子病历精简脱敏数据_500行.xlsx} 的表头一致。抽出来的理由很单纯：
 * 它是一张纯静态表，零逻辑、零依赖，却占着 {@code RecordServiceImpl} 顶部三十行，
 * 而读代码的人最需要在那里看到的是业务编排。</p>
 *
 * <p>「证型 → pattern（兼容旧表头）」这一条是有意的：同一字段在历史表里有两种列名，
 * 不兼容的话旧数据会整列读不到。</p>
 */
final class ExcelHeaderFields {

    /** 构建期填充、完成后不可变（与 EntityTypes 同做法） */
    static final Map<String, String> MAP;

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
        MAP = Collections.unmodifiableMap(header);
    }

    private ExcelHeaderFields() {
    }
}
