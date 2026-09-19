package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.OperationLog;

import java.util.List;
import java.util.Map;

/**
 * 操作日志审计（批F·7.5）：读 operation_log 表分页筛选 + 导出。
 */
public interface ILogService {

    /** 分页查询：返回 {list, total} */
    Map<String, Object> page(String action, String keyword, int page, int size);

    /** 导出（按同样筛选，不分页） */
    List<OperationLog> listForExport(String action, String keyword);

    /** 导出 CSV 文本 */
    byte[] exportCsv(String action, String keyword);
}
