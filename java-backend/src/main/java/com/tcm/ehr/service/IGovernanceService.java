package com.tcm.ehr.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CleanResultVO;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据治理服务：术语归一、数据清洗、标准数据集导出
 */
public interface IGovernanceService extends IService<Record> {

    /** 术语归一（疾病/证候/症状/中药/方剂 -> 标准术语） */
    EsTermNormalizer.NormalizeResult normalize(String type, String term);

    /** 数据清洗5步流水线（去重/字段清理/格式规整/隔离/术语归一兜底） */
    CleanResultVO clean(List<String> recordIds);

    /** 标准数据集导出（仅合格病历 + 脱敏）；无合格数据返回 null */
    ExportedFile export(ExportDTO dto) throws IOException;

    /** 数据集预览：过滤结果总数 + 前10条样本 */
    Map<String, Object> previewDataset(ExportDTO dto);

    /** 治理状态统计：质控合格/已治理/待治理 */
    Map<String, Object> governanceStats();

    /** 导出文件载体 */
    record ExportedFile(String filename, byte[] content) {
    }
}
