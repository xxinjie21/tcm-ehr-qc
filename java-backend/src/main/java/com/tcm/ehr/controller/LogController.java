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

import java.util.List;
import java.util.Map;

/**
 * 操作日志审计：分页查询、类型选项、CSV 导出。
 *
 * <p>§七 L3：归档清理（{@code POST /api/logs/purge}）已删 —— 日志只增不删，
 * 无界增长为已知未闭环项，清理只能由运维人工归档。</p>
 *
 * <p>数据源唯一：{@code operation_log} 表。§七 L2 起不再有文件副本，
 * 导出与查询都直接读库。</p>
 */
@RestController
@RequiredArgsConstructor
public class LogController {

    private final ILogService logService;

    /**
     * 分页查询操作日志。
     *
     * <p>【权限：仅管理员】</p>
     *
     * @param action  操作类型，空表示不限
     * @param keyword 关键字，匹配操作人/对象/详情
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return total=总条数；list=当前页记录
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(logService.page(action, keyword, page, size));
    }

    /**
     * 查询操作类型选项。
     *
     * <p>【权限：仅管理员】取库中出现过的值，前端据此渲染下拉，避免写死清单与调用点漂移。</p>
     *
     * @return 去重后的操作类型列表
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs/actions")
    public Result<List<String>> actions() {
        return Result.ok(logService.actions());
    }

    /**
     * 导出操作日志为 CSV。
     *
     * <p>【权限：仅管理员】文件流直出，不套 Result 包装。</p>
     *
     * @param action  操作类型，空表示导出全部
     * @param keyword 关键字
     * @return 带 UTF-8 BOM 的 CSV 字节流
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/logs/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String action,
                                         @RequestParam(required = false) String keyword) {
        byte[] content = logService.exportCsv(action, keyword);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-logs.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }
}
