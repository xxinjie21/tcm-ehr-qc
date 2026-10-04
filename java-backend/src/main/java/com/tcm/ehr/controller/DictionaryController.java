package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireOrgRole;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.PageSizeGuard;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.TermTypes;
import com.tcm.ehr.domain.dto.DictProposalDTOs;
import com.tcm.ehr.domain.po.DictArchiveVersion;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictProposalDiffVO;
import com.tcm.ehr.domain.vo.DictProposalVO;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.DictArchiveService;
import com.tcm.ehr.service.DictProposalService;
import com.tcm.ehr.service.IDictionaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 术语词典：基线查询、管理员直写导入、提案与归档版本（批次 17）。
 *
 * <p><b>写基线只有两条路</b>：① 管理员直写 {@code /import}（特权通道，不生成提案）；
 * ② 成员提交提案 → 组长审核通过后合并。<b>普通成员没有第三条路</b>。</p>
 *
 * <p>历史版本不再由「每次导入留一份备份」提供（{@code dictionary_backups} 已废弃），
 * 改由归档版本体系：每次基线合并后留一份快照，每组每 type 最多 5 份。</p>
 */
@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryController {

    private final IDictionaryService dictionaryService;
    private final DictProposalService proposalService;
    private final DictArchiveService archiveService;
    private final OperationLogger operationLogger;

    /** 校验术语类型，非法返回 null（调用方据此回 4001） */
    private static ResponseEntity<Result<String>> badType(String type) {
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return null;
    }

    // ================================================================ 查询

    /**
     * 词典分页查询（供词典页翻页 / 输入联想 / 质控规则下拉）。
     *
     * <p>【权限：登录即可】按标准词与别名模糊匹配。不传 {@code page} 返回全部命中，
     * 传 {@code page} 则分页。</p>
     */
    @GetMapping("/terms")
    public ResponseEntity<Result<Map<String, Object>>> terms(
            @RequestParam("type") String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) throws IOException {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(
                dictionaryService.searchTerms(type, keyword, page, PageSizeGuard.clamp(size))));
    }

    /**
     * 导出「小组基线」全量词条，供前端存成本地个人词典。
     *
     * <p>【权限：登录即可】前端拿到后可在本地编辑；改动要生效必须走提案流程
     * （本地词典不会自动同步回小组基线）。</p>
     */
    @GetMapping("/baseline/export")
    public ResponseEntity<Result<List<Map<String, Object>>>> exportBaseline(
            @RequestParam("type") String type) {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TermEntry e : proposalService.exportBaseline(RequestUtils.currentOrgId(), type)) {
            out.add(Map.of(
                    "standardTerm", e.getStandardTerm() == null ? "" : e.getStandardTerm(),
                    "code", e.getCode() == null ? "" : e.getCode(),
                    "source", e.getSource() == null ? "" : e.getSource(),
                    "aliases", e.getAliases() == null ? List.of() : e.getAliases()));
        }
        return ResponseEntity.ok(Result.ok(out));
    }

    // ================================================================ 管理员直写

    /**
     * 上传词典文件 → 只解析返回词条，<b>不落库</b>（普通成员的批量导入入口）。
     *
     * <p>【权限：登录即可】普通成员按约定<b>不能直接改小组基线</b>（必须走提案审核）。
     * 但这个接口不碰基线 —— 它只把文件解析成词条列表返回，前端把它们存进
     * <b>本机个人词典</b>（localStorage，与小组基线解耦）。成员若觉得这份词表值得
     * 推广，再到「我的词典」提交为提案，由组长审核合并。既给了批量导入的便利，
     * 又不绕过审核约定，也不写任何组织数据。</p>
     *
     * <p>Excel 需服务端 POI 解析（浏览器无 xlsx 能力），故放在这里而非纯前端。</p>
     *
     * @param file 词典文件（.xlsx/.xls/.csv/.json）
     * @param type 术语类型
     * @return { terms: [{standardTerm, code, source, aliases}], failures: [{row, reason}] }
     */
    @PostMapping("/parse")
    public ResponseEntity<Result<Map<String, Object>>> parse(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type) throws IOException {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        IDictionaryService.ParseResult parsed = dictionaryService.parseTerms(type, file);
        List<Map<String, Object>> terms = new ArrayList<>();
        for (TermEntry e : parsed.terms) {
            terms.add(Map.of(
                    "standardTerm", e.getStandardTerm() == null ? "" : e.getStandardTerm(),
                    "code", e.getCode() == null ? "" : e.getCode(),
                    "source", e.getSource() == null ? "" : e.getSource(),
                    "aliases", e.getAliases() == null ? List.of() : e.getAliases()));
        }
        return ResponseEntity.ok(Result.ok(Map.of(
                "terms", terms,
                "failures", parsed.failures)));
    }

    /**
     * 管理员直写导入（不生成提案，属特权通道）。
     *
     * <p>【权限：仅管理员】用于批量种子数据与基础层词典播种；写入成功后
     * <b>自动生成归档版本</b>并计入 5 份限额。</p>
     *
     * <p>{@code target=base} 写基础层（{@code org_id=''}，全局通用词库，
     * 与组织层的归档限额<b>独立</b>）；缺省或 {@code target=org} 写当前组织。
     * 刻意不开放任意 {@code orgId} 入参 —— 否则就是「管理员可写任意组织词库」的越权面。</p>
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/import")
    public ResponseEntity<Result<ImportResultVO>> importDict(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "target", required = false) String target) throws IOException {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        boolean base = "base".equalsIgnoreCase(target);
        if (target != null && !base && !"org".equalsIgnoreCase(target)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "target 只能为 base 或 org"));
        }
        String orgId = base ? "" : RequestUtils.currentOrgId();
        ImportResultVO vo = dictionaryService.importDictionary(type, file, orgId);
        // 直写同样纳入归档体系：生成快照并执行 5 份限额
        int versionNo = archiveService.archive(orgId, type,
                dictionaryService.currentTerms(orgId, type), null,
                RequestUtils.currentUsername(), "管理员直写导入");
        vo.setArchiveVersion(versionNo);
        operationLogger.log("词典导入", type + (base ? "(基础层)" : ""),
                "成功" + vo.getImported() + "条，失败" + vo.getFailed() + "条，归档 v" + versionNo);
        return ResponseEntity.ok(Result.ok(vo));
    }

    // ================================================================ 提案

    /**
     * 提交基线更新提案（携带完整目标词典）。
     *
     * <p>【权限：登录即可】同一 (组织, 类型) 最多 5 条待审，超出直接拒绝。</p>
     */
    @PostMapping("/proposals")
    public ResponseEntity<Result<DictProposalVO>> submitProposal(
            @Valid @RequestBody DictProposalDTOs.CreateProposalRequest body) {
        ResponseEntity<Result<String>> bad = badType(body.getType());
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        DictProposalVO vo = proposalService.submit(
                RequestUtils.currentOrgId(), body.getType(),
                toTerms(body.getTerms()), RequestUtils.currentUsername());
        return ResponseEntity.ok(Result.ok("提案已提交，等待组长审核", vo));
    }

    /**
     * 提案列表。成员只看自己提交的，组长看全组。
     *
     * <p>【权限：登录即可】</p>
     */
    @GetMapping("/proposals")
    public ResponseEntity<Result<List<DictProposalVO>>> listProposals(
            @RequestParam(value = "status", required = false) String status) {
        boolean isOwner = RequestUtils.isOrgOwner();
        String orgId = isOwner ? null : RequestUtils.currentOrgId();
        return ResponseEntity.ok(Result.ok(proposalService.list(
                orgId, status, isOwner, RequestUtils.currentUsername())));
    }

    /**
     * 提案与当前基线的差异（实时计算，不落表）。
     *
     * <p>【权限：登录即可】仅提交者本人与组长可看。</p>
     */
    @GetMapping("/proposals/{id}/diff")
    public ResponseEntity<Result<DictProposalDiffVO>> diff(@PathVariable("id") String id) {
        var p = proposalService.get(id);
        if (p == null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "提案不存在"));
        }
        boolean canSee = RequestUtils.isOrgOwner() || p.getSubmitUserId().equals(RequestUtils.currentUserId());
        if (!canSee) {
            return ResponseEntity.badRequest().body(Result.error(403, "无权查看该提案"));
        }
        return ResponseEntity.ok(Result.ok(proposalService.diff(id)));
    }

    /**
     * 在线编辑自己提交的提案内容（只影响提案，不动基线）。
     *
     * <p>【权限：登录即可】服务端强制校验「操作人 == 提交者」，且仅待审提案可编辑。</p>
     */
    @PutMapping("/proposals/{id}/terms")
    public ResponseEntity<Result<Void>> editProposalTerms(
            @PathVariable("id") String id,
            @Valid @RequestBody DictProposalDTOs.UpdateTermsRequest body) {
        proposalService.editTerms(id, toTerms(body.getTerms()), RequestUtils.currentUsername());
        return ResponseEntity.ok(Result.ok("提案内容已更新", null));
    }

    /**
     * 审核提案：通过则合并进基线并生成归档版本；拒绝则作废并安排 7 天后清理快照。
     *
     * <p>【权限：仅组织所有者】</p>
     */
    @RequireOrgRole("owner")
    @PostMapping("/proposals/{id}/audit")
    public ResponseEntity<Result<DictProposalVO>> audit(
            @PathVariable("id") String id,
            @Valid @RequestBody DictProposalDTOs.AuditRequest body) {
        boolean approve = Boolean.TRUE.equals(body.getApprove());
        if (!approve && (body.getComment() == null || body.getComment().isBlank())) {
            return ResponseEntity.badRequest().body(Result.error(4001, "拒绝时必须填写理由"));
        }
        DictProposalVO vo = proposalService.audit(id, approve, body.getComment(),
                RequestUtils.currentUsername(), true);
        operationLogger.log(approve ? "提案合并" : "提案驳回", id, body.getComment());
        return ResponseEntity.ok(Result.ok(approve ? "已通过并合并入基线" : "已驳回", vo));
    }

    // ================================================================ 归档版本

    /**
     * 归档版本列表（每组每 type 最多 5 份快照，元信息永久保留）。
     *
     * <p>【权限：登录即可】</p>
     */
    @GetMapping("/archives")
    public ResponseEntity<Result<List<DictArchiveVersion>>> archives(
            @RequestParam("type") String type) {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(
                archiveService.list(RequestUtils.currentOrgId(), type)));
    }

    /**
     * 基于历史归档版本生成一份<b>新提案</b>（不直接还原基线）。
     *
     * <p>【权限：仅组织所有者】回滚走提案是为了不绕过审核；生成后仍需再走一次审核流程。</p>
     */
    @RequireOrgRole("owner")
    @PostMapping("/archives/{versionNo}/rollback")
    public ResponseEntity<Result<DictProposalVO>> rollbackTo(
            @PathVariable("versionNo") int versionNo,
            @RequestParam("type") String type) {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        DictProposalVO vo = proposalService.rollbackTo(
                RequestUtils.currentOrgId(), type, versionNo, RequestUtils.currentUsername());
        operationLogger.log("归档回滚", type + " v" + versionNo, "生成回滚提案 " + vo.getId());
        return ResponseEntity.ok(Result.ok("已基于该版本生成回滚提案，请审核", vo));
    }

    /**
     * 强制重建当前组织某一类词典的 ES 索引（不落库、不改词条）。
     *
     * <p>【权限：仅管理员】用于「库里词条正确、但索引落后」的自愈。</p>
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/reindex")
    public ResponseEntity<Result<Map<String, Object>>> reindex(@RequestParam("type") String type)
            throws IOException {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(dictionaryService.reindex(type)));
    }

    // ================================================================ 内部

    /** 前端传来的词条 Map 列表 → TermEntry 列表（跳过标准词为空项） */
    private static List<TermEntry> toTerms(List<Map<String, Object>> rows) {
        List<TermEntry> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> m : rows) {
            Object std = m == null ? null : m.get("standardTerm");
            if (std == null || std.toString().isBlank()) {
                continue;
            }
            TermEntry e = new TermEntry();
            e.setStandardTerm(std.toString().trim());
            Object code = m.get("code");
            e.setCode(code == null || code.toString().isBlank() ? null : code.toString().trim());
            Object src = m.get("source");
            e.setSource(src == null ? "" : src.toString());
            Object al = m.get("aliases");
            List<String> aliases = new ArrayList<>();
            if (al instanceof List<?> list) {
                for (Object a : list) {
                    if (a != null && !a.toString().isBlank()) {
                        aliases.add(a.toString().trim());
                    }
                }
            }
            e.setAliases(aliases);
            out.add(e);
        }
        return out;
    }
}
