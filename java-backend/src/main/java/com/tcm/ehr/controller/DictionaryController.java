package com.tcm.ehr.controller;

import com.tcm.ehr.common.utils.PageSizeGuard;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.TermTypes;
import com.tcm.ehr.service.IDictionaryService;
import com.tcm.ehr.common.utils.OperationLogger;
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
import java.util.Map;

/**
 * 术语词典：导入、查询、版本回滚、备份列表。
 *
 * <p>词典按组织存放于 {@code dictionary_terms}（当前组织无自有词条时回退基础层），
 * 每次导入自动存档。{@code type} 非法统一返回 400 + code=4001。</p>
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
     * 查询术语（供词典页分页、输入联想、质控规则下拉）。
     *
     * <p>【权限：登录即可】按标准词与别名做模糊匹配。</p>
     *
     * <p><b>两种模式</b>：不传 {@code page}（或传 {@code <=0}）→ 返回全部命中，
     * 供输入联想与规则下拉取完整候选；传 {@code page} → 分页，供词典页翻页。</p>
     *
     * @param type    术语类型
     * @param keyword 关键字，为空表示不过滤
     * @param page    页码，从 1 开始；默认 0 = 不分页、返回全部
     * @param size    每页条数，仅 page&gt;0 时生效
     * @return terms=本页词条列表；total=命中总数
     */
    @GetMapping("/terms")
    public ResponseEntity<Result<Map<String, Object>>> terms(
            @RequestParam("type") String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size) throws IOException {
        // 1. 类型校验（keyword 可空 = 不过滤；page<=0 = 不分页，返回全部命中）
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(
                dictionaryService.searchTerms(type, keyword, page, PageSizeGuard.clamp(size))));
    }

    // POST /rollback 与 GET /backups 已随 dictionary_backups 表废弃（批次 17）：
    // 回滚改为「基于归档版本生成提案 → 组长审核合并」，历史版本列表改为 GET /archives。

    /**
     * 强制重建<b>当前组织</b>某一类词典的 ES 索引。
     *
     * <p><b>仅管理员</b>：它会清掉并重灌该组织的索引文档，重建空窗期归一返回 503，
     * 属于影响全组织可用性的操作，不交给「能改词典就能触发」的人。</p>
     *
     * <p>只重建当前组织这一层，基础层不连带处理 —— 否则一次手动操作会让所有组织
     * 同时进入重建空窗。</p>
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/reindex")
    public ResponseEntity<Result<Map<String, Object>>> reindex(@RequestParam("type") String type)
            throws IOException {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(dictionaryService.reindex(type)));
    }
}
