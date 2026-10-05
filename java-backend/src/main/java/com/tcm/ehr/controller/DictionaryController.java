package com.tcm.ehr.controller;

import com.tcm.ehr.common.annotation.RequireOrgRole;
import com.tcm.ehr.common.annotation.RequireRole;
import com.tcm.ehr.common.domain.Result;
import com.tcm.ehr.common.exception.ForbiddenException;
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
import lombok.extern.slf4j.Slf4j;
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
 *
 * 写基线只有两条路：① 管理员直写 /import（特权通道，不生成提案）；
 *
 * ② 成员提交提案 → 组长审核通过后合并。普通成员没有第三条路。
 *
 *
 * 历史版本不再由「每次导入留一份备份」提供（dictionary_backups 已废弃），
 *
 * 改由归档版本体系：每次基线合并后留一份快照，每组每 type 最多 5 份。
 */
@RestController
@RequestMapping("/api/dictionary")
@Slf4j
@RequiredArgsConstructor
public class DictionaryController {

    private final IDictionaryService dictionaryService;
    private final DictProposalService proposalService;
    private final DictArchiveService archiveService;
    private final com.tcm.ehr.service.IDictionaryLintService lintService;
    private final OperationLogger operationLogger;
    /** 词典写门槛要查 DB 里的成员授权位（注解只能看 JWT 里的 role），故不用注解 */
    private final com.tcm.ehr.service.IOrgPermissionService orgPermission;

    /**
     * 校验术语类型，非法返回 null（调用方据此回 4001）。
     *
     * null 与空白视为「不按类型过滤」并放行。
     *
     * ⚠️ TermTypes.ALL 是 Set.of(...)，不接受 null，contains(null) 会抛 NPE 变成 500。
     * 所以参数可空的端点（本类的 type 可选）必须先挡掉 null，
     * 否则「省略参数」比「传错参数」错得更离谱。
     */
    private static ResponseEntity<Result<String>> badType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (!TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return null;
    }

    /**
     * 词典写门槛：管理员 / 组织所有者 / 被授权成员（批次 6 工作项 4）。
     *
     * 与 {@code QcController.requireRuleWrite} 同构：不做成注解，因为它要查 DB 里的
     * organization_members.can_write_dictionary，注解只能看 JWT 里的 role。
     *
     * ⚠️ 基础层（org_id=''）不在此列 —— 它影响所有组织，只允许管理员直写，见 /import。
     */
    private void requireDictWrite() {
        if (RequestUtils.isAdmin() || orgPermission.canWriteDictionary(RequestUtils.currentUserId())) {
            return;
        }
        throw new ForbiddenException("无权修改本组织的词典（需管理员、组织所有者或被授权成员）");
    }

    // ================================================================ 查询

    /**
     * 词典分页查询（供词典页翻页 / 输入联想 / 质控规则下拉）。
     *
     *
     * 【权限：登录即可】按标准词与别名模糊匹配。不传 page 返回全部命中，
     *
     * 传 page 则分页。
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
     *
     * 【权限：登录即可】前端拿到后可在本地编辑；改动要生效必须走提案流程
     *
     * （本地词典不会自动同步回小组基线）。
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
     * 上传词典文件 → 只解析返回词条，不落库（普通成员的批量导入入口）。
     *
     *
     * 【权限：登录即可】普通成员按约定不能直接改小组基线（必须走提案审核）。
     *
     * 但这个接口不碰基线 —— 它只把文件解析成词条列表返回，前端把它们存进
     * 本机个人词典（localStorage，与小组基线解耦）。成员若觉得这份词表值得
     * 推广，再到「我的词典」提交为提案，由组长审核合并。既给了批量导入的便利，
     * 又不绕过审核约定，也不写任何组织数据。
     *
     *
     * Excel 需服务端 POI 解析（浏览器无 xlsx 能力），故放在这里而非纯前端。
     *
     *
     * 响应里一并带上词表体检结果（批次 21）：同名重复、别名含标准词、编码形态等
     * 都是导入不会报错的缺陷，只在这里指出来最合适 —— 用户正准备入库，
     * 看到提示还能改；等入库后再查，脏数据已经进库并进了 ES 索引。
     *
     * @param file 词典文件（.xlsx/.xls/.csv/.json）
     * @param type 术语类型
     * @return { terms: [{standardTerm, code, source, aliases}], failures: [{row, reason}], lint: {...} }
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
                "failures", parsed.failures,
                // 体检是纯计算，失败不该拦住「看看文件里有什么」，故降级为空结果
                "lint", safeLint(type, parsed.terms))));
    }

    /**
     * 体检失败不阻断解析。
     *
     *
     * 体检只是「提醒」，如果它自己抛异常就把整个解析带崩，用户连文件内容都看不到 ——
     *
     * 那是本末倒置。所以这里兜住并返回一份空结论，让用户至少能下载/查看解析结果。
     */
    private Object safeLint(String type, List<TermEntry> terms) {
        try {
            return lintService.lint(type, terms);
        } catch (Exception e) {
            com.tcm.ehr.domain.vo.DictionaryLintVO empty = new com.tcm.ehr.domain.vo.DictionaryLintVO();
            empty.setTotal(terms == null ? 0 : terms.size());
            return empty;
        }
    }

    /**
     * 管理员直写导入（不生成提案，属特权通道）。
     *
     *
     * 【权限：仅管理员】用于批量种子数据与基础层词典播种；写入成功后
     *
     * 自动生成归档版本并计入 5 份限额。
     *
     *
     * 【权限：管理员 / 所有者 / 授权成员】组织层（缺省或 target=org）走三档门槛；
     * target=base 写基础层，影响所有组织，仍限管理员。
     * 刻意不开放任意 orgId 入参 —— 否则就是「管理员可写任意组织词库」的越权面。
     */
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
        if (base) {
            // 基础层是各组织共用的系统基线：放开给「被授权成员」会变成「一个人改所有人的词库」
            if (!RequestUtils.isAdmin()) {
                throw new ForbiddenException("基础层词典仅管理员可导入");
            }
        } else {
            requireDictWrite();
        }
        String orgId = base ? "" : RequestUtils.currentOrgId();
        ImportResultVO vo = dictionaryService.importDictionary(type, file, orgId);
        // ⚠️ 到这里导入**已经提交**（词条进库 + ES 重建 + 版本已记）。后面的归档与审计
        //    都只是随后的记账动作，失败不能把整个请求变成 500 —— 那会让用户以为导入没成，
        //    而实际已经生效（重试还会再合并一次）；静默丢弃又会让归档体系与词典内容对不上
        //    （5 份限额与回滚历史错位）。故各自兜住：成功照常 200，原因写进返回体，栈入日志。
        Integer versionNo = null;
        try {
            // 直写同样纳入归档体系：生成快照并执行 5 份限额
            versionNo = archiveService.archive(orgId, type,
                    dictionaryService.currentTerms(orgId, type), null,
                    RequestUtils.currentUsername(), "管理员直写导入");
            vo.setArchiveVersion(versionNo);
        } catch (Exception e) {
            // 完整栈入日志：这条路径此前只回一个追踪码，排障时拿不到栈
            log.error("[词典] {} (org={}) 导入已成功，但归档版本生成失败：{}", type, orgId, e.getMessage(), e);
            vo.setArchiveWarning("导入已成功，但归档版本生成失败：" + e.getMessage());
        }
        try {
            operationLogger.log("词典导入", type + (base ? "(基础层)" : ""),
                    "成功" + vo.getImported() + "条，失败" + vo.getFailed() + "条，归档 "
                            + (versionNo == null ? "失败" : "v" + versionNo));
        } catch (Exception e) {
            log.warn("[词典] {} (org={}) 导入审计日志写入失败：{}", type, orgId, e.getMessage(), e);
        }
        return ResponseEntity.ok(Result.ok(vo));
    }

    // ================================================================ 提案

    /**
     * 提交基线更新提案（携带完整目标词典）。
     *
     *
     * 【权限：登录即可】同一 (组织, 类型) 最多 5 条待审，超出直接拒绝。
     *
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
 * 【权限：登录即可】
 *
 * type 可选：前端词典页的类型筛选是全局过滤（基线与归档都按它取数），
 * 提案列表跟随同一筛选器，页面上才不会出现「切了类型但提案还是别的类型」的割裂感。
 * 非法类型按 4001 拒绝，与 /import、/archives 同一口径。
 */
    @GetMapping("/proposals")
    public ResponseEntity<Result<List<DictProposalVO>>> listProposals(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type) {
        ResponseEntity<Result<String>> bad = badType(type);
        if (bad != null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        boolean isOwner = RequestUtils.isOrgOwner();
        DictProposalDTOs.ProposalQuery query = new DictProposalDTOs.ProposalQuery();
        // 组织条件必填，不再有「owner 看全库」：看全库等于把别组提案的完整词条快照
        // 交出去。组长看「本组 + 基础层」，成员只看自己提交的
        query.setOrgId(RequestUtils.currentOrgId());
        query.setIncludeBaseLayer(isOwner);
        query.setStatus(status);
        query.setType(type);
        query.setIsOwner(isOwner);
        query.setSubmitUserId(RequestUtils.currentUsername());
        return ResponseEntity.ok(Result.ok(proposalService.list(query)));
    }

/**
 * 提案与当前基线的差异（实时计算，不落表）。
 *
 * 【权限：登录即可】仅提交者本人与组长可看。
 *
 * ⚠️ 比对对象必须是 username，不是 userId：submit_user_id 列存的是
 * RequestUtils.currentUsername()（见本类 submit），而 JWT 的 subject 是 user.getId()，
 * 两者是不同的值。此前这里误用 currentUserId() 去比，普通成员永远打不开
 * 自己提案的差异，只会拿到「无权查看该提案」。list 与 editTerms 两处都比 username，
 * 只有 diff 写错。回归测试见 DictionaryControllerDiffTest。
 */
    @GetMapping("/proposals/{id}/diff")
    public ResponseEntity<Result<DictProposalDiffVO>> diff(@PathVariable("id") String id) {
        var p = proposalService.get(id);
        if (p == null) {
            return ResponseEntity.badRequest().body(Result.error(4001, "提案不存在"));
        }
        boolean canSee = RequestUtils.isOrgOwner() || p.getSubmitUserId().equals(RequestUtils.currentUsername());
        if (!canSee) {
            return ResponseEntity.badRequest().body(Result.error(403, "无权查看该提案"));
        }
        return ResponseEntity.ok(Result.ok(proposalService.diff(id)));
    }

    /**
     * 在线编辑自己提交的提案内容（只影响提案，不动基线）。
     *
     *
     * 【权限：登录即可】服务端强制校验「操作人 == 提交者」，且仅待审提案可编辑。
     *
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
     *
     * 【权限：管理员 / 所有者 / 授权成员】不挂 RequireOrgRole：本路由的 {id} 是提案号，
     * 不是机构 id，无法做路径机构比对。权限与归属都由 service 判 ——
     * 管理员可审任意提案（含基础层），其余需「本组织提案 + can_write_dictionary」。
     *
     */
    @PostMapping("/proposals/{id}/audit")
    public ResponseEntity<Result<DictProposalVO>> audit(
            @PathVariable("id") String id,
            @Valid @RequestBody DictProposalDTOs.AuditRequest body) {
        boolean approve = Boolean.TRUE.equals(body.getApprove());
        if (!approve && (body.getComment() == null || body.getComment().isBlank())) {
            return ResponseEntity.badRequest().body(Result.error(4001, "拒绝时必须填写理由"));
        }
        DictProposalVO vo = proposalService.audit(id, approve, body.getComment(),
                RequestUtils.currentUsername());
        operationLogger.log(approve ? "提案合并" : "提案驳回", id, body.getComment());
        return ResponseEntity.ok(Result.ok(approve ? "已通过并合并入基线" : "已驳回", vo));
    }

    // ================================================================ 归档版本

    /**
     * 归档版本列表（每组每 type 最多 5 份快照，元信息永久保留）。
     *
     *
     * 【权限：登录即可】
     *
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
     * 基于历史归档版本生成一份新提案（不直接还原基线）。
     *
     *
     * 【权限：管理员 / 所有者 / 授权成员】回滚走提案是为了不绕过审核；生成后仍需再走一次审核流程。
     *
     */
    @PostMapping("/archives/{versionNo}/rollback")
    public ResponseEntity<Result<DictProposalVO>> rollbackTo(
            @PathVariable("versionNo") int versionNo,
            @RequestParam("type") String type) {
        // 回滚只生成本组织的提案，与「本组织词典可写」同一门槛；
        // 不挂 @RequireOrgRole：本路由的路径变量是版本号，没有机构 id 可比对
        requireDictWrite();
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
     * 强制重建 ES 索引（不落库、不改词条）。
     *
     *
     * 【权限：仅管理员】用于「库里词条正确、但索引落后」的自愈。
     * type 省略 = 全部类型；org 省略 = 当前组织，org=* = 词典里出现过的全部组织（灾难恢复）。
     *
     */
    @RequireRole(roles = {"管理员"})
    @PostMapping("/reindex")
    public ResponseEntity<Result<Map<String, Object>>> reindex(
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "org", required = false) String org) throws IOException {
        // type 可空（= 全部类型），故不能用 badType 直接挡空；只校验「填了就必须合法」
        if (type != null && !type.isBlank() && !TermTypes.ALL.contains(type)) {
            return ResponseEntity.badRequest().body(Result.error(4001, "术语类型非法"));
        }
        return ResponseEntity.ok(Result.ok(dictionaryService.reindex(type, org)));
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
