package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import jakarta.validation.Valid;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.CreateRecordDTO;
import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
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
 * 病历数据：批量导入、单条新增、原始查看、修改、删除、多条件查询。
 *
 * <p>导入进度查询按 {@code LogController} 之外的审计链路处理；写操作统一记入操作日志。</p>
 */
@RestController
@RequiredArgsConstructor
public class RecordController {

    private final IRecordService recordService;
    private final OperationLogger operationLogger;

    /**
     * 批量导入病历文件。
     *
     * <p>【权限：仅管理员】单文件 ≤50MB、单次 ≤20 个；21 字段全一致的记录视为重复并跳过。</p>
     *
     * @param files       病历文件（.xlsx/.xls）
     * @param autoExtract 是否在导入后自动投递结构化解析任务
     * @return taskId=导入任务ID；summary=本轮成功/失败条数与失败明细
     */
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

    /**
     * 查询导入任务进度。
     *
     * <p>【权限：仅管理员】进度存在内存中，服务重启后查询返回 404。</p>
     *
     * @param taskId 导入任务ID
     * @return status=任务状态；processed/success/failed=处理进度
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/records/import/{taskId}/status")
    public ResponseEntity<Result<ImportStatusVO>> importStatus(@PathVariable String taskId) {
        ImportStatusVO vo = recordService.importStatus(taskId);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "任务不存在或服务重启后任务状态丢失"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 单条新增病历。
     *
     * <p>【权限：仅管理员】</p>
     *
     * @param dto 21 字段原始记录，登记号必填
     * @return id=新病历ID
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/records")
    public Result<CreateRecordVO> createRecord(@RequestBody CreateRecordDTO dto) {
        return Result.ok("新增成功", recordService.createRecord(dto));
    }

    /**
     * 查看病历原始数据。
     *
     * <p>【权限：登录即可 + 数据域】21 个原始字段只读，附结构化数据与评分结果；不存在返回 404。</p>
     *
     * @param recordId 病历ID
     * @return 21 原始字段 + structuredData + score/grade
     */
    @GetMapping("/api/records/raw/{recordId}")
    public ResponseEntity<Result<RawRecordVO>> rawRecord(@PathVariable String recordId) {
        RawRecordVO vo = recordService.getRawRecord(recordId);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 修改病历的结构化数据。
     *
     * <p>【权限：仅管理员】只允许改 structuredData，携带原始字段按只读冲突返回 1007。</p>
     *
     * @param recordId 病历ID
     * @param body     仅接受 structuredData 键
     * @return 无数据体，仅成功标记
     */
    @RequireRole(roles = {"管理员"})
    @PutMapping("/api/records/{recordId}")
    public Result<Void> updateRecord(@PathVariable String recordId, @RequestBody Map<String, Object> body) {
        recordService.updateRecord(recordId, body);
        operationLogger.log("病历修改", recordId, "更新结构化数据");
        return Result.ok("修改成功", null);
    }

    /**
     * 按 ID 批量删除病历。
     *
     * <p>【权限：仅管理员】先清复核任务再删，避免外键约束失败。</p>
     *
     * @param dto ids=待删除的病历ID集合
     * @return deletedCount=实际删除条数
     */
    @RequireRole(roles = {"管理员"})
    @DeleteMapping("/api/records")
    public Result<DeleteRecordsVO> deleteRecords(@RequestBody DeleteRecordsDTO dto) {
        DeleteRecordsVO vo = recordService.deleteRecords(dto);
        operationLogger.log("病历删除", "共" + vo.getDeletedCount() + "条", null);
        return Result.ok("删除成功", vo);
    }

    /**
     * 按筛选范围批量删除病历。
     *
     * <p>【权限：仅管理员】条件全空时拒绝，避免误删全库。</p>
     *
     * @param filters department/dateRange/pattern/grade，至少一项非空
     * @return deletedCount=实际删除条数
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/records/delete-by-filter")
    public Result<DeleteRecordsVO> deleteByFilter(@RequestBody FiltersDTO filters) {
        DeleteRecordsVO vo = recordService.deleteByFilter(filters);
        operationLogger.log("病历删除", "按范围", "共" + vo.getDeletedCount() + "条");
        return Result.ok("删除成功", vo);
    }

    /**
     * 多条件分页查询病历。
     *
     * <p>【权限：登录即可 + 数据域】审核员恒为待复核域。</p>
     *
     * @param dto 查询条件与分页参数
     * @return total=总条数；records=当前页摘要列表
     */
    @PostMapping("/api/records/search")
    public Result<SearchVO> search(@Valid @RequestBody SearchDTO dto) {
        return Result.ok(recordService.searchRecords(dto));
    }
}
