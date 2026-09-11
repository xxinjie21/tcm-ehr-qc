package com.tcm.ehr.util;

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
 * 操作日志（文档必做2）：关键操作（清洗/导出/词典导入/回滚等）追加写入 logs/operation.log
 * 格式：时间 | 操作人 | 操作内容；持久化存储、重启不丢失；成员A的 GET /api/logs/export 读取同一文件
 */
@Slf4j
@Component
public class OperationLogger {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${log.operation-file:logs/operation.log}")
    private String logFile;

    public synchronized void log(String operator, String action) {
        try {
            Path path = Paths.get(logFile);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String line = LocalDateTime.now().format(TS) + " | " + nvl(operator) + " | " + nvl(action)
                    + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[操作日志] 写入失败: {}", e.getMessage());
        }
    }

    private String nvl(String s) {
        return s == null ? "unknown" : s;
    }
}
