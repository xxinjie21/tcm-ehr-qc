package com.tcm.ehr.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.CleanResultVO;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据清洗服务：术语标准化、批量清洗、标准数据集导出与预览、清洗状态统计。
 */
public interface IGovernanceService extends IService<Record> {

    /**
     * 把一个术语归一到标准写法。
     *
     * @param type 术语类型
     * @param term 待归一术语
     * @return standardTerm=标准词（未命中时为原文）；source=词典来源；level=命中层级
     */
    EsTermNormalizer.NormalizeResult normalize(String type, String term);

    /**
     * 执行清洗流水线。
     *
     * @param recordIds 限定病历集合
     * @param filters   范围条件，recordIds 为空时生效
     * @return 各步处理条数
     */
    CleanResultVO clean(List<String> recordIds, com.tcm.ehr.domain.dto.FiltersDTO filters);

    /**
     * 导出标准数据集。
     *
     * @param dto format=csv/json；filters=范围条件
     * @return 文件名与内容；范围内无合格病历返回 {@code null}
     */
    ExportedFile export(ExportDTO dto) throws IOException;

    /**
     * 预览导出结果。
     *
     * @param dto format=csv/json；filters=范围条件
     * @return total=命中条数；sample=前 10 条样本
     */
    Map<String, Object> previewDataset(ExportDTO dto);

    /**
     * 清洗状态统计。
     *
     * @return qualified=质控合格数；pendingGovern=待清洗数；governedCount=已清洗数
     */
    Map<String, Object> governanceStats();

    /** 导出产物 */
    record ExportedFile(String filename, byte[] content) {
    }
}
