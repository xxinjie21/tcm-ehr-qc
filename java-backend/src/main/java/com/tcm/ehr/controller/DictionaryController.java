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
 * 术语词典：导入、PDF 转换预览、查询、版本回滚、备份列表。
 *
 * <p>词典以 JSON 文件存放，每次导入自动备份。{@code type} 非法统一返回 400 + code=4001。</p>
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {

    private final IDictionaryService dictionaryService;
    private final OperationLogger operationLogger;


    /**
     * 导入术语库文件。
     *
     * <p>【权限：仅管理员】整文件覆盖，导入前自动备份旧版本；行级失败不中断，整批照常返回。</p>
     *
     * @param file 术语文件（.xlsx/.xls/.csv/.json）
     * @param type 术语类型（疾病/证候/症状/中药/方剂）
     * @return total=总行数；imported/failed=成功与失败条数；failures=失败明细
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/import")
    public ResponseEntity<Result<ImportResultVO>> importDict(@RequestParam("file") MultipartFile file,
                                                             @RequestParam("type") String type) throws IOException {
        // 1. 类型先校验（4001 与全局的 400 不同码，前端按它提示"类型选错"）
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        // 文件格式不支持等 IllegalArgumentException 由 GlobalExceptionHandler 统一返回 400
        // 2. 导入（内部含合并、备份、覆盖写、重建索引与失败补偿）
        ImportResultVO vo = dictionaryService.importDictionary(type, file);
        // 3. 留痕：词典变更影响全库归一结果，必须可追溯
        operationLogger.log("词典导入", type, "成功" + vo.getImported()
                + "条，失败" + vo.getFailed() + "条");
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 把国标 PDF 转换成候选术语供预览。
     *
     * <p>【权限：仅管理员】预览不落库，确认后走 {@code /import} 入库。LLM 未开启或通道不可达时
     * 返回 400 与可读提示，引导改用离线脚本或 JSON 直传。</p>
     *
     * @param file 国标 PDF 文件
     * @param type 术语类型
     * @return candidates=候选术语；failed=抽取失败明细
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/convert")
    public ResponseEntity<Result<ConvertPreviewVO>> convert(@RequestParam("file") MultipartFile file,
                                                           @RequestParam("type") String type) throws IOException {
        // 1. 类型校验
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        // 2. 只出候选不落库；LLM 未开/不可达在 service 里抛 400
        ConvertPreviewVO vo = dictionaryService.convertFromPdf(type, file);
        // 3. 留痕并注明"待确认入库"
        operationLogger.log("词典转换", type, "候选" + vo.getCandidates().size()
                + "条，失败" + vo.getFailed().size() + "条（待确认入库）");
        return ResponseEntity.ok(Result.ok(vo));
    }

    /**
     * 查询术语（供页面展示与输入联想）。
     *
     * <p>【权限：登录即可】按标准词与别名做模糊匹配。</p>
     *
     * @param type    术语类型
     * @param keyword 关键字，为空表示不过滤
     * @return terms=命中的术语列表
     */
    @GetMapping("/terms")
    public ResponseEntity<Result<Map<String, Object>>> terms(@RequestParam("type") String type,
                                                             @RequestParam(value = "keyword", required = false) String keyword)
            throws IOException {
        // 1. 类型校验（keyword 可空 = 不过滤，最多返回 100 条）
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(Map.of("terms", dictionaryService.searchTerms(type, keyword))));
    }

    /**
     * 回滚到指定历史版本。
     *
     * <p>【权限：仅管理员】立即生效并重建索引；备份文件不存在返回 400。</p>
     *
     * @param body type=术语类型；backupFilename=备份文件名
     * @return 无数据体，仅成功标记
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/rollback")
    public ResponseEntity<Result<Void>> rollback(@RequestBody Map<String, String> body) throws IOException {
        String type = body.get("type");
        String backupFilename = body.get("backupFilename");
        // 1. 类型校验
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        // 2. 先确认备份存在（含路径穿越前缀校验），再执行覆盖
        if (backupFilename == null || !dictionaryService.backupExists(type, backupFilename)) {
            return ResponseEntity.badRequest().body(Result.error("备份文件不存在"));
        }
        // 3. 回滚并重建索引
        dictionaryService.rollback(type, backupFilename);
        operationLogger.log("词典回滚", type, "恢复备份 " + backupFilename);
        return ResponseEntity.ok(Result.ok(null));
    }

    /**
     * 查询历史版本列表。
     *
     * <p>【权限：登录即可】</p>
     *
     * @param type 术语类型
     * @return backups=版本项（导入时间、词条数、相对当前增减）
     */
    @GetMapping("/backups")
    public ResponseEntity<Result<Map<String, Object>>> backups(@RequestParam("type") String type) throws IOException {
        // 1. 类型校验；无备份时返回空列表而不是 404
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        // 2. 按时间倒序返回（含词条数与相对当前增减）
        return ResponseEntity.ok(Result.ok(Map.of("backups", dictionaryService.listBackups(type))));
    }
}
