package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.CreateRecordDTO;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.vo.CreateRecordVO;
import com.tcm.ehr.domain.vo.DeleteRecordsVO;
import com.tcm.ehr.domain.vo.ImportStatusVO;
import com.tcm.ehr.domain.vo.ImportTaskVO;
import com.tcm.ehr.domain.vo.RawRecordVO;
import com.tcm.ehr.domain.vo.SearchVO;
import com.tcm.ehr.service.IRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 病历数据（批F · 成员A 线）。
 * 7.1 导入/新增/进度 · 7.2 原始查看 · 7.3 修改/删除 · 7.4 多条件查询。
 * 7.5 日志审计见 {@code LogController}。
 */
@RestController
@RequiredArgsConstructor
public class RecordController {

    private final IRecordService recordService;
    private final OperationLogger operationLogger;

    /** 病历批量导入（多文件 .xlsx/.xls）；autoExtract=导入后自动结构化解析（后台任务）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/records/import")
    public Result<ImportTaskVO> importRecords(@RequestParam("files") MultipartFile[] files,
                                              @RequestParam(value = "autoExtract", defaultValue = "false") boolean autoExtract) {
        ImportTaskVO vo = recordService.importRecords(files, autoExtract);
        operationLogger.log("病历导入", "文件" + files.length + "个",
                "成功" + vo.getSummary().getSuccess() + "条，失败" + vo.getSummary().getFailed() + "条"
                        + (vo.getAutoExtractTaskId() == null ? "" : "，已提交后台解析"));
        return Result.ok(vo);
    }

    /** 导入进度查询（内存态，服务重启返回 404）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/records/import/{taskId}/status")
    public ResponseEntity<Result<ImportStatusVO>> importStatus(@PathVariable String taskId) {
        ImportStatusVO vo = recordService.importStatus(taskId);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "任务不存在或服务重启后任务状态丢失"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /** 单条新增病历；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/records")
    public Result<CreateRecordVO> createRecord(@RequestBody CreateRecordDTO dto) {
        return Result.ok("新增成功", recordService.createRecord(dto));
    }

    /** 原始病历只读查看（21 字段）；【权限：登录即可 + 数据域】 */
    @GetMapping("/api/records/raw/{recordId}")
    public ResponseEntity<Result<RawRecordVO>> rawRecord(@PathVariable String recordId) {
        RawRecordVO vo = recordService.getRawRecord(recordId);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /** 病历修改（仅 structuredData，原始字段只读）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PutMapping("/api/records/{recordId}")
    public Result<Void> updateRecord(@PathVariable String recordId, @RequestBody Map<String, Object> body) {
        recordService.updateRecord(recordId, body);
        operationLogger.log("病历修改", recordId, "更新结构化数据");
        return Result.ok("修改成功", null);
    }

    /** 批量删除病历；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @DeleteMapping("/api/records")
    public Result<DeleteRecordsVO> deleteRecords(@RequestBody DeleteRecordsDTO dto) {
        DeleteRecordsVO vo = recordService.deleteRecords(dto);
        operationLogger.log("病历删除", "共" + vo.getDeletedCount() + "条", null);
        return Result.ok("删除成功", vo);
    }

    /** 多条件分页查询；【权限：登录即可 + 数据域（审核员恒为待复核域）】 */
    @PostMapping("/api/records/search")
    public Result<SearchVO> search(@RequestBody SearchDTO dto) {
        return Result.ok(recordService.searchRecords(dto));
    }
}
