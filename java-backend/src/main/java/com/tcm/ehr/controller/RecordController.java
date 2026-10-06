package com.tcm.ehr.controller;

import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api/records")
public class RecordController {

    private final IRecordService recordService;
    private final OperationLogger operationLogger;

    /**
     * 批量导入病历文件。
     *
     * <p>【权限：登录即可】单文件 ≤50MB、单次 ≤20 个；21 字段全一致的记录视为重复并跳过。</p>
     *
     * @param files       病历文件（.xlsx/.xls）
     * @param autoExtract 是否在导入后自动投递结构化解析任务
     * @return taskId=导入任务ID；summary=本轮成功/失败条数与失败明细
     */
    @PostMapping("/import")
    public Result<ImportTaskVO> importRecords(@RequestParam("files") MultipartFile[] files,
                                              @RequestParam(value = "autoExtract", defaultValue = "false") boolean autoExtract) {
        // 1. 导入并逐行给失败原因 2. 留痕（含是否已提交后台解析）
        ImportTaskVO vo = recordService.importRecords(files, autoExtract);
        operationLogger.log("病历导入", "文件" + files.length + "个",
                "成功" + vo.getSummary().getSuccess() + "条，失败" + vo.getSummary().getFailed() + "条"
                        + (vo.getAutoExtractTaskId() == null ? "" : "，已提交后台解析"));
        return Result.ok(vo);
    }

    /**
     * 单条新增病历。
     *
     * <p>【权限：登录即可】</p>
     *
     * @param dto 21 字段原始记录，登记号必填；字段级校验由 @Valid 触发
     * @return id=新病历ID
     */
    @PostMapping("")
    public Result<CreateRecordVO> createRecord(@Valid @RequestBody CreateRecordDTO dto) {
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
    @GetMapping("/raw/{recordId}")
    public ResponseEntity<Result<RawRecordVO>> rawRecord(@PathVariable String recordId) {
        // 1. 取原始数据；不存在与无权限都归为 404：数据域过滤在 service 内完成，不泄露"存在但看不到"
        RawRecordVO vo = recordService.getRawRecord(recordId);
        if (vo == null) {
            return ResponseEntity.status(404).body(Result.error(404, "病历不存在"));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 修改病历的结构化数据。
     *
     * 【权限：登录即可】只允许改 structuredData，携带原始字段按只读冲突返回 400。
     *
     * <p>入参是「部分更新」语义的有序 Map（带原始字段要报冲突，不能收窄成 DTO），
     * 所以无法用 Bean Validation，改在入口做守卫：空体与不带 structuredData 一律 400。</p>
     *
     * @param recordId 病历ID
     * @param body     仅接受 structuredData 键
     * @return 无数据体，仅成功标记
     */
    @PutMapping("/{recordId}")
    public Result<Void> updateRecord(@PathVariable String recordId, @RequestBody Map<String, Object> body) {
        // 1. 入口守卫：空体 / 不带 structuredData 直接拒，避免空更新被当成成功
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("请提交要修改的内容（structuredData）");
        }
        if (!body.containsKey("structuredData")) {
            throw new IllegalArgumentException("只允许修改结构化数据（structuredData）");
        }
        // 2. 只改结构化数据；带原始字段的冲突由 service 抛 IllegalArgumentException（400）
        recordService.updateRecord(recordId, body);
        // 3. 留痕：改了什么病历必须可查。
        //    对标 D3：带上对象标识，这样病历详情页才能查出「这条病历被谁改过」，
        //    而不是让人去审计页翻「病历修改」再肉眼比对 detail。
        operationLogger.logOnObject("病历修改", recordId, "更新结构化数据", "record", recordId);
        return Result.ok("修改成功", null);
    }

    /**
     * 按 ID 批量删除病历。
     *
     * 【权限：登录即可】先清复核任务再删，避免外键约束失败。
     * 数据域在 service 内先行过滤：请求体里不属于当前组的 id 会被剔除，不计入删除数。
     *
     * @param dto ids=待删除的病历ID集合
     * @return deletedCount=实际删除条数
     */
    @DeleteMapping("")
    public Result<DeleteRecordsVO> deleteRecords(@Valid @RequestBody DeleteRecordsDTO dto) {
        // 1. 删（service 内先按数据域过滤，再清复核任务、删病历）
        DeleteRecordsVO vo = recordService.deleteRecords(dto);
        // 2. 留痕：请求条数与实际删除条数分开记，跨组尝试才看得出来
        operationLogger.log("病历删除",
                "请求" + dto.ids().size() + "条 / 实际删除" + vo.getDeletedCount() + "条", null);
        return Result.ok("删除成功", vo);
    }

    /**
     * 按筛选范围批量删除病历。
     *
     * <p>【权限：登录即可】条件全空时拒绝，避免误删全库。</p>
     *
     * @param filters department/dateRange/pattern/grade，至少一项非空
     * @return deletedCount=实际删除条数
     */
    @PostMapping("/delete-by-filter")
    public Result<DeleteRecordsVO> deleteByFilter(@Valid @RequestBody FiltersDTO filters) {
        // 1. 按范围删；条件全空会被 service 拒绝（防误删全库）2. 留痕
        DeleteRecordsVO vo = recordService.deleteByFilter(filters);
        // 留痕要写明范围是否跨机构：管理员「看全部」时这条会把全部机构一起删掉，
        // 日志里只写「按范围」事后看不出影响面
        operationLogger.log("病历删除", "按范围", "共" + vo.getDeletedCount() + "条"
                + (com.tcm.ehr.common.utils.RequestUtils.viewAllOrgs() ? "（全部机构）" : ""));
        return Result.ok("删除成功", vo);
    }

    /**
     * 多条件分页查询病历。
     *
     * <p>【权限：登录即可 + 数据域】按当前组织过滤。</p>
     *
     * @param dto 查询条件与分页参数
     * @return total=总条数；records=当前页摘要列表
     */
    @PostMapping("/search")
    public Result<SearchVO> search(@Valid @RequestBody SearchDTO dto) {
        return Result.ok(recordService.searchRecords(dto));
    }
}
