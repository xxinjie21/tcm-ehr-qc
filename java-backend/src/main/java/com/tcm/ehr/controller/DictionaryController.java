package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.TermTypes;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.vo.ConvertPreviewVO;
import com.tcm.ehr.domain.vo.ImportResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 术语词典：导入 / 查询 / 版本回滚 / 备份列表
 * type非法统一返回 HTTP 400 + code=4001（附录B错误码表）
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {

    private final IDictionaryService dictionaryService;
    private final OperationLogger operationLogger;


    /** 上传术语库文件（Excel/CSV/JSON），解析校验后写入词典（自动备份旧版本）；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/import")
    public ResponseEntity<Result<ImportResultVO>> importDict(@RequestParam("file") MultipartFile file,
                                                             @RequestParam("type") String type) throws IOException {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        // 文件格式不支持等 IllegalArgumentException 由 GlobalExceptionHandler 统一返回 400
        ImportResultVO vo = dictionaryService.importDictionary(type, file);
        operationLogger.log("词典导入", type, "成功" + vo.getImported()
                + "条，失败" + vo.getFailed() + "条");
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * PDF 智能转换（国标 PDF 免手工转 JSON）：上传 PDF → 抽文本 → LLM 提取候选 → 返回预览。
     * <b>预览不落库</b>，管理员确认后再走 {@code /import} 入库。
     * AI 能力未开启（总控 llm.enabled 关闭）或通道连不上时回 HTTP 400 + 友好提示，
     * 引导走离线脚本或 JSON 直传；提示文案不出现配置项名；【权限：仅管理员】
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/convert")
    public ResponseEntity<Result<ConvertPreviewVO>> convert(@RequestParam("file") MultipartFile file,
                                                           @RequestParam("type") String type) throws IOException {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        ConvertPreviewVO vo = dictionaryService.convertFromPdf(type, file);
        operationLogger.log("词典转换", type, "候选" + vo.getCandidates().size()
                + "条，失败" + vo.getFailed().size() + "条（待确认入库）");
        return ResponseEntity.ok(Result.ok(vo));
    }

    /** 只读查询/自动补全（读词典 JSON 文件，标准词+别名关键字模糊匹配） */
    @GetMapping("/terms")
    public ResponseEntity<Result<Map<String, Object>>> terms(@RequestParam("type") String type,
                                                             @RequestParam(value = "keyword", required = false) String keyword)
            throws IOException {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(Map.of("terms", dictionaryService.searchTerms(type, keyword))));
    }

    /** 版本回滚：从备份文件恢复指定版本；【权限：仅管理员】 */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/rollback")
    public ResponseEntity<Result<Void>> rollback(@RequestBody Map<String, String> body) throws IOException {
        String type = body.get("type");
        String backupFilename = body.get("backupFilename");
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        if (backupFilename == null || !dictionaryService.backupExists(type, backupFilename)) {
            return ResponseEntity.badRequest().body(Result.error("备份文件不存在"));
        }
        dictionaryService.rollback(type, backupFilename);
        operationLogger.log("词典回滚", type, "恢复备份 " + backupFilename);
        return ResponseEntity.ok(Result.ok(null));
    }

    /** 备份版本列表 */
    @GetMapping("/backups")
    public ResponseEntity<Result<Map<String, Object>>> backups(@RequestParam("type") String type) throws IOException {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(Map.of("backups", dictionaryService.listBackups(type))));
    }
}
