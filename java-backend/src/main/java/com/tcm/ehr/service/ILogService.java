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

    /** 库中实际出现过的操作类型（筛选下拉的动态取值，UX-19） */
    List<String> actions();

    /** 导出（按同样筛选，不分页） */
    List<OperationLog> listForExport(String action, String keyword);

    /** 导出 CSV 文本 */
    byte[] exportCsv(String action, String keyword);

    /**
     * 归档并清理：先把 {@code beforeDate}（yyyy-MM-dd，不含当天）之前的日志导出为归档 CSV
     * 到 {@code logs/}，成功后再删除。
     *
     * @return {deleted: 删除条数, archivedFile: 归档文件名（无删除时为空串）}
     */
    java.util.Map<String, Object> purgeBefore(String beforeDate);

    /** 某操作人最近 n 条操作（升序返回，供 AI 助手注入个人操作上下文，批I·I3） */
    List<OperationLog> listRecentByOperator(String operator, int limit);
}
