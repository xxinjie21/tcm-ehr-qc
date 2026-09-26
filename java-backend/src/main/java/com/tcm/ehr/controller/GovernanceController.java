package com.tcm.ehr.controller;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.CleanDTO;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.dto.NormalizeDTO;
import com.tcm.ehr.service.IGovernanceService;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.domain.vo.CleanResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 数据清洗：术语标准化、批量清洗、标准数据集导出与预览、清洗状态统计。
 *
 * <p>清洗只规整与标记，不填充医生未书写的内容，也不删除病历。</p>
 */
@RestController
@RequiredArgsConstructor
public class GovernanceController {

    private final IGovernanceService governanceService;
    private final OperationLogger operationLogger;
    private final ObjectMapper objectMapper;


    /**
     * 把一个术语归一到标准写法。
     *
     * <p>【权限：登录即可】未命中词典时原样返回；{@code type} 非法返回 400 + code=4001。</p>
     *
     * @param dto type=术语类型；term=待归一术语
     * @return standardTerm=标准词（未命中时为原文）；source=词典来源；level=命中层级；code=国标代码
     */
    @PostMapping("/api/governance/normalize")
    public ResponseEntity<Result<Map<String, Object>>> normalize(@RequestBody NormalizeDTO dto) {
        if (dto.getTerm() == null || dto.getTerm().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error("请输入术语"));
        }
        if (dto.getType() == null || !com.tcm.ehr.common.config.EntityTypes.dictKeys().contains(dto.getType())) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        var r = governanceService.normalize(dto.getType(), dto.getTerm());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("standardTerm", r.standardTerm());
        data.put("source", r.source());
        data.put("level", r.level());
        data.put("code", r.code());
        return ResponseEntity.ok(Result.ok(data));
    }

    /**
     * 对指定范围执行清洗。
     *
     * <p>【权限：仅管理员】五步流水线：去重（只标记）→ 字段清理 → 格式规整 → 脏数据隔离 → 术语归一。</p>
     *
     * @param dto recordIds=限定病历集合；filters=范围条件，二选一
     * @return total/deduped/repaired/isolated/normalized=各步处理条数
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/governance/clean")
    public Result<CleanResultVO> clean(@RequestBody CleanDTO dto) {
        CleanResultVO result = governanceService.clean(dto.getRecordIds(), dto.getFilters());
        operationLogger.log("数据清洗", RecordFilter.describe(dto == null ? null : dto.getFilters()), "共" + result.getTotal() + "条，去重" + result.getDeduped()
                + "，隔离" + result.getIsolated() + "，归一" + result.getNormalized());
        return Result.ok(result);
    }

    /**
     * 导出标准数据集。
     *
     * <p>【权限：仅管理员】只导出质控合格且已清洗的病历；范围内无合格数据时返回 400 + code=2001。</p>
     *
     * @param dto format=csv/json；filters=范围条件
     * @return 文件流（带 UTF-8 文件名的附件）
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/export/dataset")
    public ResponseEntity<byte[]> export(@RequestBody ExportDTO dto) throws IOException {
        IGovernanceService.ExportedFile file = governanceService.export(dto);
        if (file == null) {
            operationLogger.log("数据集导出", RecordFilter.describe(dto == null ? null : dto.getFilters()),
                    "被拒：筛选范围内无合格病历");
            Result<Void> err = Result.error(2001, "质控未通过，禁止导出数据集（筛选范围内无合格病历）");
            return ResponseEntity.status(400)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(err));
        }
        operationLogger.log("数据集导出", file.filename(), null);
        String encoded = URLEncoder.encode(file.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.content());
    }

    /**
     * 预览导出结果。
     *
     * <p>【权限：仅管理员】只返回条数与样本，不生成文件。</p>
     *
     * @param dto format=csv/json；filters=范围条件
     * @return total=命中条数；sample=前 10 条样本
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/export/dataset/preview")
    public Result<Map<String, Object>> preview(@RequestBody ExportDTO dto) {
        return Result.ok(governanceService.previewDataset(dto));
    }

    /**
     * 查询清洗状态统计。
     *
     * <p>【权限：仅管理员】</p>
     *
     * @return qualified=质控合格数；pendingGovern=待清洗数；governedCount=已清洗数
     */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/governance/stats")
    public Result<Map<String, Object>> governanceStats() {
        return Result.ok(governanceService.governanceStats());
    }
}
