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
 * 操作日志审计实现（批F·7.5 / 批I·I2）：读 operation_log（批A·C5 双写入库）做分页筛选、导出、归档清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogServiceImpl implements ILogService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final OperationLogMapper operationLogMapper;

    /** 归档 CSV 目录（清理前先落盘） */
    @Value("${log.archive-dir:logs}")
    private String archiveDir;

    @Override
    public Map<String, Object> page(String action, String keyword, int page, int size) {
        Page<OperationLog> p = operationLogMapper.selectPage(
                new Page<>(Math.max(page, 1), Math.max(size, 1)), buildWrapper(action, keyword));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", p.getTotal());
        result.put("list", p.getRecords());
        return result;
    }

    @Override
    public List<String> actions() {
        return operationLogMapper.selectDistinctActions();
    }

    @Override
    public List<OperationLog> listForExport(String action, String keyword) {
        return operationLogMapper.selectList(buildWrapper(action, keyword));
    }

    @Override
    public byte[] exportCsv(String action, String keyword) {
        return csvBytes(listForExport(action, keyword));
    }

    @Override
    public Map<String, Object> purgeBefore(String beforeDate) {
        LocalDate date;
        try {
            date = LocalDate.parse(beforeDate.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
        String boundary = date + " 00:00:00";
        QueryWrapper<OperationLog> w = new QueryWrapper<OperationLog>()
                .lt("log_time", boundary)
                .orderByAsc("log_time");
        List<OperationLog> rows = operationLogMapper.selectList(w);

        Map<String, Object> result = new LinkedHashMap<>();
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

    @Override
    public List<OperationLog> listRecentByOperator(String operator, int limit) {
        if (operator == null || operator.isBlank() || limit <= 0) {
            return List.of();
        }
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
            throw new IllegalStateException("归档失败，未执行清理：" + e.getMessage(), e);
        }
    }

    /** 生成带 UTF-8 BOM 的 CSV 字节（Excel 正确识别中文） */
    private byte[] csvBytes(List<OperationLog> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("操作时间,操作人,角色,操作类型,操作对象,详情,IP\n");
        for (OperationLog l : rows) {
            sb.append(csv(l.getLogTime() == null ? "" : l.getLogTime().format(TS))).append(',')
                    .append(csv(l.getOperator())).append(',')
                    .append(csv(l.getRole())).append(',')
                    .append(csv(l.getAction())).append(',')
                    .append(csv(l.getTarget())).append(',')
                    .append(csv(l.getDetail())).append(',')
                    .append(csv(l.getIp())).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private QueryWrapper<OperationLog> buildWrapper(String action, String keyword) {
        QueryWrapper<OperationLog> w = new QueryWrapper<>();
        if (action != null && !action.isBlank()) {
            w.eq("action", action.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like("operator", k).or().like("target", k).or().like("detail", k));
        }
        w.orderByDesc("log_time");
        return w;
    }

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
