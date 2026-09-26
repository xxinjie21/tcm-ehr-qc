package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.OperationLog;

import java.util.List;
import java.util.Map;

/**
 * 操作日志审计：分页查询、类型选项、导出、归档清理。
 */
public interface ILogService {

    /**
     * 分页查询。
     *
     * @param action  操作类型，空表示不限
     * @param keyword 关键字，匹配操作人/对象/详情
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return total=总条数；list=当前页记录
     */
    Map<String, Object> page(String action, String keyword, int page, int size);

    /**
     * 操作类型去重值，供筛选下拉动态渲染。
     *
     * @return 库中出现过的操作类型
     */
    List<String> actions();

    /**
     * 按同样条件取全量记录（导出用，不分页）。
     *
     * @param action  操作类型
     * @param keyword 关键字
     * @return 命中的日志记录
     */
    List<OperationLog> listForExport(String action, String keyword);

    /**
     * 导出为 CSV 文本。
     *
     * @param action  操作类型
     * @param keyword 关键字
     * @return 带 UTF-8 BOM 的 CSV 字节
     */
    byte[] exportCsv(String action, String keyword);

    /**
     * 归档并清理指定日期之前的日志。
     *
     * @param beforeDate 截止日期（yyyy-MM-dd），该日之前的记录被清理
     * @return deleted=删除条数；archivedFile=归档文件名（无记录时为空串）
     */
    java.util.Map<String, Object> purgeBefore(String beforeDate);

    /**
     * 某操作人最近的 n 条日志，供 AI 助手拼装"我做了什么"上下文。
     *
     * @param operator 操作人用户名
     * @param limit    最多返回条数
     * @return 按时间倒序的日志记录
     */
    List<OperationLog> listRecentByOperator(String operator, int limit);
}
