package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 日志审计（批F·7.5）：读库分页筛选 + 导出（文件流，不套 Result）。
 * 数据源为 operation_log（批A·C5 双写入库）；文件 logs/operation.log 为兜底。
 */
@RestController
@RequiredArgsConstructor
public class LogController {

    private final ILogService logService;

    /** 操作日志分页查询；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(logService.page(action, keyword, page, size));
    }

    /** 操作日志导出（CSV 文件流，不套 Result）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String action,
                                         @RequestParam(required = false) String keyword) {
        byte[] content = logService.exportCsv(action, keyword);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=operation_logs.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }
}
