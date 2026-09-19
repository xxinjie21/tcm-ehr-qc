package com.tcm.ehr.controller;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.domain.dto.CleanDTO;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.dto.NormalizeDTO;
import com.tcm.ehr.service.IGovernanceService;
import com.tcm.ehr.common.utils.OperationLogger;
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
 * 数据治理：术语标准化 / 数据清洗 / 标准数据集导出
 */
@RestController
@RequiredArgsConstructor
public class GovernanceController {

    private final IGovernanceService governanceService;
    private final OperationLogger operationLogger;
    private final ObjectMapper objectMapper;


    /** 术语归一（疾病/证候/症状/中药/方剂 -> 标准术语）；type非法返回 HTTP 400 + code=4001 */
    @PostMapping("/api/governance/normalize")
    public ResponseEntity<Result<Map<String, String>>> normalize(@RequestBody NormalizeDTO dto) {
        if (dto.getTerm() == null || dto.getTerm().isBlank()) {
            return ResponseEntity.badRequest().body(Result.error("term不能为空"));
        }
        if (dto.getType() == null || !List.of("disease", "pattern", "symptom", "herb", "formula").contains(dto.getType())) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        var r = governanceService.normalize(dto.getType(), dto.getTerm());
        return ResponseEntity.ok(Result.ok(Map.of("standardTerm", r.standardTerm(), "source", r.source())));
    }

    /** 数据清洗（去重/字段清理/格式规整/隔离/术语归一）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/governance/clean")
    public Result<CleanResultVO> clean(@RequestBody CleanDTO dto) {
        CleanResultVO result = governanceService.clean(dto.getRecordIds());
        operationLogger.log("数据清洗", null, "共" + result.getTotal() + "条，去重" + result.getDeduped()
                + "，隔离" + result.getIsolated() + "，归一" + result.getNormalized());
        return Result.ok(result);
    }

    /**
     * 标准数据集导出（CSV/JSON文件流）
     * 无合格数据时返回 HTTP 400 + code=2001（质控未通过，禁止导出数据集）；【权限：仅管理员】
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/export/dataset")
    public ResponseEntity<byte[]> export(@RequestBody ExportDTO dto) throws IOException {
        IGovernanceService.ExportedFile file = governanceService.export(dto);
        if (file == null) {
            operationLogger.log("数据集导出", null, "被拒：筛选范围内无合格病历");
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

    /** 数据集预览（原型[预览数据集]）：过滤结果总数 + 前10条样本；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/api/export/dataset/preview")
    public Result<Map<String, Object>> preview(@RequestBody ExportDTO dto) {
        return Result.ok(governanceService.previewDataset(dto));
    }

    /** 治理状态统计：质控合格/已治理/待治理；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @GetMapping("/api/governance/stats")
    public Result<Map<String, Object>> governanceStats() {
        return Result.ok(governanceService.governanceStats());
    }
}
