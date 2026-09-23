package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.PurgeLogDTO;
import com.tcm.ehr.service.ILogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 日志审计（批F·7.5 / 批I·I2）：读库分页筛选 + 导出（文件流，不套 Result）+ 归档清理。
 * 数据源为 operation_log（批A·C5 双写入库）；文件 logs/operation.log 为兜底。
 */
@RestController
@RequiredArgsConstructor
public class LogController {

    private final ILogService logService;
    private final OperationLogger operationLogger;

    /** 操作日志分页查询；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(logService.page(action, keyword, page, size));
    }

    /**
     * 操作类型选项：取库中实际出现过的值，前端下拉据此渲染，
     * 避免前端写死清单与后端调用点漂移（UX-19）；【权限：仅管理员】
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs/actions")
    public Result<List<String>> actions() {
        return Result.ok(logService.actions());
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

    /** 归档并清理指定日期之前的日志（先归档落盘、成功再删）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/logs/purge")
    public Result<Map<String, Object>> purge(@RequestBody PurgeLogDTO dto) {
        if (dto == null || dto.getBeforeDate() == null || dto.getBeforeDate().isBlank()) {
            throw new IllegalArgumentException("请提供清理截止日期 beforeDate");
        }
        Map<String, Object> result = logService.purgeBefore(dto.getBeforeDate());
        Object archived = result.get("archivedFile");
        operationLogger.log("日志清理", "清理至 " + dto.getBeforeDate(),
                "删除 " + result.get("deleted") + " 条"
                        + (archived == null || String.valueOf(archived).isBlank() ? "" : "，归档 " + archived));
        return Result.ok("清理完成", result);
    }
}
