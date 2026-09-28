package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.mapper.OperationLogMapper;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志审计实现：读 operation_log 做分页筛选与导出。
 * §七 L2/L3：归档文件与 purge 均已删，现在只剩「读库 + 导 CSV」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements ILogService {

    /** 页面展示用的时间格式 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OperationLogMapper operationLogMapper;

    /**
     * 分页查询操作日志。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return total=总条数、list=当前页记录
     */
    @Override
    public Map<String, Object> page(String action, String keyword, int page, int size) {
        // 1. 分页参数兜底为 1（非法分页会让 SQL 报错），条件走统一 wrapper
        Page<OperationLog> p = operationLogMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.max(size, 1)), buildWrapper(action, keyword));
        // 2. 固定顺序装 total / list，前端按 key 取
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotal());
        result.put("list", p.getRecords());
        return result;
    }

    /**
     * 取全部操作类型，供筛选下拉框使用。
     *
     * @return 去重后的操作类型列表
     */
    @Override
    public List<String> actions() {
        return operationLogMapper.selectDistinctActions();
    }

    /**
     * 按条件取全部日志（不分页），供导出使用。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @return 命中条件的日志列表
     */
    @Override
    public List<OperationLog> listForExport(String action, String keyword) {
        return operationLogMapper.selectList(buildWrapper(action, keyword));
    }

    /**
     * 导出日志为 CSV 字节流。
     *
     * @param action 操作类型，为空表示不限
     * @param keyword 关键字，匹配操作人 / 操作对象 / 详情
     * @return 带 UTF-8 BOM 的 CSV 内容
     */
    @Override
    public byte[] exportCsv(String action, String keyword) {
        return csvBytes(listForExport(action, keyword));
    }

    /**
     * 取某操作人最近的若干条日志，供 AI 助手理解上下文。
     *
     * @param operator 操作人
     * @param limit 最多返回条数
     * @return 按时间倒序的日志列表；入参非法时返回空列表
     */
    @Override
    public List<OperationLog> listRecentByOperator(String operator, int limit) {
        // 1. 入参缺失直接返回空，避免拼出无意义的查询
        if (operator == null || operator.isBlank() || limit <= 0) {
            return List.of();
        }
        // 2. 按时间倒序取前 limit 条
        QueryWrapper<OperationLog> w = new QueryWrapper<OperationLog>()
                .eq("operator", operator)
                .orderByDesc("log_time")
                .last("LIMIT " + limit);
        return operationLogMapper.selectList(w);
    }

    /** 生成带 UTF-8 BOM 的 CSV 字节（Excel 正确识别中文） */
    private byte[] csvBytes(List<OperationLog> rows) {
        // 1. 写表头
        StringBuilder sb = new StringBuilder();
        sb.append("操作时间,操作人,角色,操作类型,操作对象,详情\n");
        // 2. 逐条拼行（字段值按 CSV 规则转义）
        for (OperationLog l : rows) {
            sb.append(csv(l.getLogTime() == null ? "" : l.getLogTime().format(TS))).append(',')
                    .append(csv(l.getOperator())).append(',')
                    .append(csv(l.getRole())).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append('\n');
        }
        // 3. 前置 UTF-8 BOM 后返回
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    /** 组装筛选条件：操作类型精确匹配，关键字模糊匹配操作人 / 操作对象 / 详情，统一时间倒序 */
    private QueryWrapper<OperationLog> buildWrapper(String action, String keyword) {
        QueryWrapper<OperationLog> w = new QueryWrapper<>();
        // 1. 操作类型精确匹配
        if (action != null && !action.isBlank()) {
            w.eq("action", action.trim());
        }
        // 2. 关键字三列任一命中
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like("operator", k).or().like("target", k).or().like("detail", k));
        }
        // 3. 时间倒序
        w.orderByDesc("log_time");
        return w;
    }

    /** CSV 字段转义：含逗号 / 引号 / 换行时用引号包裹，内部引号翻倍 */
    private String csv(String s) {
        // 1. null 给空串（CSV 里空字段就是空）
        if (s == null) {
            return "";
        }
        // 2. 内部引号翻倍
        String v = s.replace("\"", "\"\"");
        // 3. 含分隔符或换行才加引号包裹
        if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
