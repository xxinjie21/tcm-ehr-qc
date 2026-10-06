package com.tcm.ehr.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import com.tcm.ehr.common.utils.PageSizeGuard;
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
@RequestMapping("/api/logs")
public class LogController {

    private final ILogService logService;

    /**
     * 分页查询操作日志。
     *
     * 【权限：登录即可】可见范围按三档：管理员看全部、组织所有者看本组织、成员只看自己
     * （实现在 LogServiceImpl，不在本方法加注解 —— 档位要查库里的成员授权位，注解表达不了）。
     *
     * @param action  操作类型，空表示不限
     * @param keyword 关键字，匹配操作人/对象/详情
     * @param operator 精确操作人，空表示不限（与三档可见范围叠加，成员传别人名只会得到空集）
     * @param startTime 起始时间（含），'yyyy-MM-dd HH:mm:ss'
     * @param endTime   结束时间（含），同上
     * @param page    页码，从 1 开始
     * @param pageSize 每页条数，上限 {@value PageSizeGuard#MAX_PAGE_SIZE}（超出按上限截断，避免 pageSize=999999 一次拉全表）
     * @return total=总条数；list=当前页记录
     */
    @GetMapping("")
    public Result<Map<String, Object>> logs(@RequestParam(required = false) String action,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String operator,
                                            @RequestParam(required = false) String startTime,
                                            @RequestParam(required = false) String endTime,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(logService.page(action, keyword, operator, startTime, endTime, page,
                PageSizeGuard.clamp(pageSize)));
    }

    /**
     * 按对象查活动流（对标 D3）：病历详情页的「活动 / 历史」用它。
     *
     * <p>【权限：登录即可】可见范围与 {@code GET /api/logs} 完全一致（同一套 buildWrapper），
     * 只额外按对象精确过滤 —— 不能因为「是按 ID 查」就绕过数据域。</p>
     *
     * @param objectType 对象类型，如 record（必填）
     * @param objectId   对象 ID（必填）
     * @return total / list（按时间倒序）
     */
    @GetMapping("/by-object")
    public Result<Map<String, Object>> byObject(@RequestParam String objectType,
                                                @RequestParam String objectId,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        if (objectType.isBlank() || objectId.isBlank()) {
            throw new IllegalArgumentException("缺少对象标识");
        }
        return Result.ok(logService.pageByObject(objectType, objectId, page,
                PageSizeGuard.clamp(pageSize)));
    }

    /**
     * 查询操作类型选项。
     *
     * <p>【权限：登录即可】取库中出现过的值（同三档），前端据此渲染下拉，避免写死清单与调用点漂移。</p>
     *
     * @return 去重后的操作类型列表
     */
    @GetMapping("/actions")
    public Result<List<String>> actions() {
        return Result.ok(logService.actions());
    }

    /**
     * 导出操作日志为 CSV。
     *
     * <p>【权限：登录即可】文件流直出（同三档），不套 Result 包装。</p>
     *
     * @param action  操作类型，空表示导出全部
     * @param keyword 关键字
     * @param operator 精确操作人，空表示不限
     * @param startTime 起始时间（含）
     * @param endTime   结束时间（含）
     * @return 带 UTF-8 BOM 的 CSV 字节流
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String action,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String operator,
                                         @RequestParam(required = false) String startTime,
                                         @RequestParam(required = false) String endTime) {
        byte[] content = logService.exportCsv(action, keyword, operator, startTime, endTime);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=audit-logs.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }
}
