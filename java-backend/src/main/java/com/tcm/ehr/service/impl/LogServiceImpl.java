package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.mapper.OperationLogMapper;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作日志审计实现：读 operation_log做分页筛选、导出、归档清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements ILogService {

    /** 页面展示用的时间格式 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 归档文件名中的时间戳格式 */
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final OperationLogMapper operationLogMapper;

    /** 归档 CSV 目录（清理前先落盘） */
    @Value("${log.archive-dir:logs}")
    private String archiveDir;

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
        Page<OperationLog> p = operationLogMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.max(size, 1)), buildWrapper(action, keyword));
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
     * 归档并清理指定日期之前的日志。
     *
     * @param beforeDate 截止日期（yyyy-MM-dd），该日 00:00:00 之前的记录被清理
     * @return deleted=清理条数、archivedFile=归档文件名（无记录时为空串）
     */
    @Override
    public Map<String, Object> purgeBefore(String beforeDate) {
        // 1. 解析截止日期
        LocalDate date;
        try {
            date = LocalDate.parse(beforeDate.trim());
        } catch (DateTimeParseException e) {
            // 入参日期格式不合法 → 转成明确的参数错误返回，不落到 500
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
        // 2. 取待清理记录（时间正序，与归档文件内顺序一致）
        String boundary = date + " 00:00:00";
        QueryWrapper<OperationLog> w = new QueryWrapper<OperationLog>()
                .lt("log_time", boundary)
                .orderByAsc("log_time");
        List<OperationLog> rows = operationLogMapper.selectList(w);

        Map<String, Object> result = new LinkedHashMap<>();
        // 3. 无记录直接返回，不产生空归档文件
        if (rows.isEmpty()) {
            result.put("deleted", 0);
            result.put("archivedFile", "");
            return result;
        }
        // 先归档落盘，成功后再删（归档失败则抛错、不删除）
        String file = writeArchive(rows);
        int deleted = operationLogMapper.delete(w);
        result.put("deleted", deleted);
        result.put("archivedFile", file);
        return result;
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

    /** 写归档 CSV 到 logs/，返回文件名；失败抛 IOException（由上层转 500，不静默丢数据） */
    private String writeArchive(List<OperationLog> rows) {
        String name = "audit-archive-" + LocalDateTime.now().format(FILE_TS) + ".csv";
        try {
            Path dir = Paths.get(archiveDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), csvBytes(rows));
            log.info("[日志清理] 已归档 {} 条到 {}", rows.size(), dir.resolve(name));
            return name;
        } catch (IOException e) {
            // 目录不可写 / 磁盘异常 → 抛出终止清理，库内数据保持原样
            throw new IllegalStateException("归档失败，未执行清理：" + e.getMessage(), e);
        }
    }

    /** 生成带 UTF-8 BOM 的 CSV 字节（Excel 正确识别中文） */
    private byte[] csvBytes(List<OperationLog> rows) {
        // 1. 写表头
        StringBuilder sb = new StringBuilder();
        sb.append("操作时间,操作人,角色,操作类型,操作对象,详情,IP\n");
        // 2. 逐条拼行（字段值按 CSV 规则转义）
        for (OperationLog l : rows) {
            sb.append(csv(l.getLogTime() == null ? "" : l.getLogTime().format(TS))).append(',')
                    .append(csv(l.getOperator())).append(',')
                    .append(csv(l.getRole())).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append(',')
                    .append(csv(l.getIp())).append('\n');
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
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        if (v.contains(",") || v.contains("\n") || v.contains("\"")) {
            return "\"" + v + "\"";
        }
        return v;
    }
}
