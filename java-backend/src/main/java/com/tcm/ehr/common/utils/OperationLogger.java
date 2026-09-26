package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.OperationLog;
import com.tcm.ehr.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 操作日志（文档必做2）：关键操作（清洗 / 导出 / 词典导入 / 词典回滚 / 人工复核 / 批量重算）
 * **文件落盘 + 入库双写**。
 *
 * <ul>
 * <li>文件：追加写 {@code logs/operation.log}，格式「时间 | 操作人 | 操作内容」，作为兜底备份，
 * 读取方为 {@code GET /api/logs/export}；</li>
 * <li>库：INSERT {@code operation_log}（log_time/operator/role/action/target/detail/ip），
 * 供审计页 {@code GET /api/logs} 分页筛选；</li>
 * <li>双写不做强一致，各自 try-catch：**以文件为准**，库写失败不阻塞业务（仅审计页缺该条展示）。</li>
 * </ul>
 *
 * 操作人 / 角色 / IP 取自 JwtInterceptor 写入的 request 属性（见 {@link RequestUtils}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 库字段长度上限（与 database-init.sql 对齐），超长截断，避免插入失败丢整条 */
    private static final int MAX_OPERATOR = 50;
    private static final int MAX_ROLE = 20;
    private static final int MAX_ACTION = 50;
    private static final int MAX_TARGET = 255;
    private static final int MAX_IP = 45;

    private final OperationLogMapper operationLogMapper;

    @Value("${log.operation-file:logs/operation.log}")
    private String logFile;

    /**
     * 记录一条关键操作（文件 + 库）
     *
     * @param action 操作类型（数据清洗 / 数据集导出 / 词典导入 / 词典回滚 / 人工复核 / 批量重算）
     * @param target 操作对象（筛选范围 / 文件名 / 词典类型 / 病历ID），可为 null
     * @param detail 操作明细，可为 null
     */
    public void log(String action, String target, String detail) {
        String operator = RequestUtils.currentUsername();
        String role = RequestUtils.currentRole();
        String ip = RequestUtils.currentIp();
        // 截断到秒：库列 DATETIME(0) 对小数秒是四舍五入，文件格式化是截断，
        // 不截断会导致同一操作在文件与库中相差 1 秒，审计对不上账
        LocalDateTime now = LocalDateTime.now().withNano(0);

        writeFile(now, operator, buildContent(action, target, detail));
        insertDb(now, operator, role, action, target, detail, ip);
    }

    /** 文件行内容：`操作类型：操作对象，操作明细`（缺省段自动省略） */
    private String buildContent(String action, String target, String detail) {
        StringBuilder sb = new StringBuilder(nvl(action));
        if (target != null && !target.isBlank()) {
            sb.append('：').append(target.trim());
        }
        if (detail != null && !detail.isBlank()) {
            sb.append('，').append(detail.trim());
        }
        return sb.toString();
    }

    /** 文件落盘（兜底备份，重启不丢失） */
    private synchronized void writeFile(LocalDateTime now, String operator, String content) {
        try {
            Path path = Paths.get(logFile);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String line = now.format(TS) + " | " + nvl(operator) + " | " + content
                    + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[操作日志] 文件写入失败: {}", e.getMessage());
        }
    }

    /** 入库（审计页数据源）；失败仅告警，不阻塞业务 */
    private void insertDb(LocalDateTime now, String operator, String role,
                          String action, String target, String detail, String ip) {
        try {
            OperationLog row = new OperationLog();
            row.setLogTime(now);
            row.setOperator(cut(operator, MAX_OPERATOR));
            row.setRole(cut(role, MAX_ROLE));
            row.setAction(cut(action, MAX_ACTION));
            row.setTarget(cut(target, MAX_TARGET));
            row.setDetail(detail == null ? null : detail.trim());
            row.setIp(cut(ip, MAX_IP));
            operationLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[操作日志] 入库失败（不影响业务，文件已留痕）: {}", e.getMessage());
        }
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String text = s.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String nvl(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
