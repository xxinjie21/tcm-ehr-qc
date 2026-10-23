package com.tcm.ehr.domain.dto;

import lombok.Data;

import java.util.Map;

/**
 * 标准数据集的导出请求参数。
 *
 * format 决定产物形态（csv 便于 Excel 打开、json 便于程序消费），filters 复用列表查询条件。
 *
 * ⚠️ 导出只取「质控合格且已清洗」的病历，filters 里的 grade 同样不生效；
 * 范围内无合格数据时返回 400 + code=2001，而不是空文件。
 */
@Data
public class ExportDTO {

    /** csv / json */
    private String format;

    /** 筛选条件：department / start / end */
    private Map<String, Object> filters;
}
