package com.tcm.ehr.controller;

import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.DictProposalDTOs;
import com.tcm.ehr.domain.po.DictProposal;
import com.tcm.ehr.domain.vo.DictProposalDiffVO;
import com.tcm.ehr.domain.vo.DictProposalVO;
import com.tcm.ehr.service.IDictArchiveService;
import com.tcm.ehr.service.IDictProposalService;
import com.tcm.ehr.service.IDictionaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.mockito.ArgumentCaptor;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提案差异查看权限的回归测试。
 *
 *
 * 事故背景：submit_user_id 列存的是 RequestUtils.currentUsername()，
 *
 * 而 JWT 的 subject 是 user.getId() —— username 与 userId 是两个不同的值
 * （见 JwtInterceptor 第 82-83 行分别 setAttribute）。控制器比对时却用了
 * currentUserId()，于是普通成员点开自己的提案时
 * submitUserId.equals(currentUserId()) 恒为 false，
 * 只能拿到「无权查看该提案」—— 差异对比功能对全部普通成员静默失效。
 * 同一控制器的 list / editTerms 两处都比的是 username，所以只有 diff 写错；
 * 这个 bug 不抛异常、不报错，只是让一个功能对目标用户永久不可用。
 *
 *
 * 所以这里刻意把 userId 与 username 绑成不同的值：
 *
 * 若有人再把比较对象改回 userId，本测试立刻失败。
 */
class DictionaryControllerDiffTest {

    private static final String USER_ID = "u-1001";      // JWT subject
    private static final String USERNAME = "alice";      // 登录名 —— 提案里存的是它

    private IDictProposalService proposalService;
    private OperationLogger operationLogger;
    private DictionaryController controller;

    @BeforeEach
    void setUp() {
        proposalService = mock(IDictProposalService.class);
        operationLogger = mock(OperationLogger.class);
        controller = new DictionaryController(
                mock(IDictionaryService.class),
                proposalService,
                mock(IDictArchiveService.class),
                // 批次 21 起 controller 多一个词表体检依赖（/parse 用它出 lint 结论）
                mock(com.tcm.ehr.service.IDictionaryLintService.class),
                operationLogger,
                // 批次 6 工作项 4 起 controller 多一个词典写权限依赖
                mock(com.tcm.ehr.service.IOrgPermissionService.class));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 绑定一个登录上下文：userId 与 username 故意不同 */
    private void bindAs(String orgRole, String username) {
        bindAs(orgRole, "org-A", username);
    }

    /** 绑定一个登录上下文：orgId / orgRole / username 都可指定（跨组织用例用） */
    private void bindAs(String orgRole, String orgId, String username) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_USER_ID, USER_ID);
        req.setAttribute(RequestUtils.ATTR_USERNAME, username);
        req.setAttribute(RequestUtils.ATTR_ROLE, RequestUtils.ROLE_USER);
        req.setAttribute(RequestUtils.ATTR_ORG_ID, orgId);
        req.setAttribute(RequestUtils.ATTR_ORG_ROLE, orgRole);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private void proposalSubmittedBy(String submitUserId) {
        DictProposal p = new DictProposal();
        p.setId("p1");
        p.setOrgId("org-A");
        p.setType("herb");
        p.setSubmitUserId(submitUserId);
        p.setStatus(DictProposal.PENDING);
        when(proposalService.get("p1")).thenReturn(p);
        when(proposalService.diff("p1")).thenReturn(new DictProposalDiffVO());
    }

    @Test
    @DisplayName("普通成员能查看自己提交的提案差异（这正是修复前的失效点）")
    void submitterCanViewOwnDiff() {
        proposalSubmittedBy(USERNAME);
        bindAs(RequestUtils.ORG_ROLE_MEMBER, USERNAME);

        ResponseEntity<?> res = controller.diff("p1");

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "提案的 submit_user_id 存的是 username，"
                        + "若这里返回「无权查看」，说明又退回用 currentUserId() 去比了");
    }

    @Test
    @DisplayName("普通成员看不到他人提案的差异")
    void memberCannotViewOthersDiff() {
        proposalSubmittedBy("bob");
        bindAs(RequestUtils.ORG_ROLE_MEMBER, USERNAME);

        ResponseEntity<?> res = controller.diff("p1");

        // 25.8：HTTP 状态码必须与业务码一致。此前是 400 配 code=403，
        // 前端按状态码分流时会把「无权」当参数错误，提示与跳转都会走错分支
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "非提交者不得查看他人提案内容（且必须是 403，不是 400）");
    }

    @Test
    @DisplayName("组长可查看组内任意成员的提案差异")
    void ownerCanViewAnyDiff() {
        proposalSubmittedBy("bob");
        bindAs(RequestUtils.ORG_ROLE_OWNER, USERNAME);

        ResponseEntity<?> res = controller.diff("p1");

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "组长审核别人的提案时必须能看到差异");
    }

    /**
     * 跨组织是这条判权真正要挡的东西。
     *
     * <p>修复前写的是 {@code isOrgOwner() || submitter.equals(username)}：{@code isOrgOwner()}
     * 只说明「我在<b>我自己</b>的组织里是组长」，与提案属于哪个组织无关 ——
     * 于是 org-B 的组长拿 org-A 的提案号就能读到别组的词条快照。
     * 同类洞在服务端 requireAuditable 与 list 已修过，diff 是漏网的第三处。</p>
     */
    @Test
    @DisplayName("别组的组长看不到本组提案的差异（跨组织读取）")
    void ownerOfAnotherOrgCannotViewDiff() {
        proposalSubmittedBy("bob");
        // 调用者是 org-B 的组长，提案属于 org-A
        bindAs(RequestUtils.ORG_ROLE_OWNER, "org-B", USERNAME);

        ResponseEntity<?> res = controller.diff("p1");

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode(),
                "组长身份必须绑定到提案自身的 org_id，否则任一组长可读任意提案");
    }

    /** 管理员跨组织可看：与 requireAuditable「管理员一律可以」同口径（此前 diff 反而不放行） */
    @Test
    @DisplayName("管理员可查看任意提案的差异")
    void adminCanViewAnyDiff() {
        proposalSubmittedBy("bob");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_USER_ID, USER_ID);
        req.setAttribute(RequestUtils.ATTR_USERNAME, "root");
        req.setAttribute(RequestUtils.ATTR_ROLE, RequestUtils.ROLE_ADMIN);
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-B");
        req.setAttribute(RequestUtils.ATTR_ORG_ROLE, RequestUtils.ORG_ROLE_OWNER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        ResponseEntity<?> res = controller.diff("p1");

        assertEquals(HttpStatus.OK, res.getStatusCode(),
                "管理员跨组织读提案差异是允许的（requireAuditable 同一口径）");
    }

    /**
     * 基础层提案（{@code org_id=''}）不属于任何组织：按 org_id 等值比时
     * 任何组织都「不等于」它，若照搬等值比较会让所有非管理员都看不到，
     * 与「基础层仅管理员」的既定口径保持一致。
     */
    @Test
    @DisplayName("基础层提案（org_id 为空）仅管理员可看差异")
    void baseLayerProposalIsAdminOnly() {
        DictProposal p = new DictProposal();
        p.setId("p1");
        p.setOrgId("");
        p.setType("herb");
        p.setSubmitUserId("bob");
        p.setStatus(DictProposal.PENDING);
        when(proposalService.get("p1")).thenReturn(p);
        when(proposalService.diff("p1")).thenReturn(new DictProposalDiffVO());

        bindAs(RequestUtils.ORG_ROLE_OWNER, USERNAME);
        assertEquals(HttpStatus.FORBIDDEN, controller.diff("p1").getStatusCode(),
                "org-A 的组长也不能看基础层提案的差异");
    }

    @Test
    @DisplayName("提案不存在时返回 400，而不是 403")
    void missingProposalIsBadRequest() {
        when(proposalService.get("nope")).thenReturn(null);
        bindAs(RequestUtils.ORG_ROLE_MEMBER, USERNAME);

        ResponseEntity<?> res = controller.diff("nope");

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
    }

    @Test
    @DisplayName("提交提案写审计日志（28.4）")
    void submitProposalWritesOperationLog() {
        DictProposalDTOs.CreateProposalRequest body = new DictProposalDTOs.CreateProposalRequest();
        body.setType("herb");
        // 三条里有一条标准词为空白：toTerms 会剔除，日志条数必须与真正入库的条数一致
        body.setTerms(List.of(
                Map.of("standardTerm", "甘草"),
                Map.of("standardTerm", "人参"),
                Map.of("standardTerm", "   ")));
        DictProposalVO vo = new DictProposalVO();
        vo.setId("p9");
        when(proposalService.submit(anyString(), eq("herb"), anyList(), anyString())).thenReturn(vo);
        bindAs(RequestUtils.ORG_ROLE_MEMBER, USERNAME);

        ResponseEntity<?> res = controller.submitProposal(body);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogger).log(eq("提案提交"), eq("herb"), detail.capture());
        assertTrue(detail.getValue().contains("共 2 条"),
                "审计日志条数应等于 toTerms 过滤后的条数，实际：" + detail.getValue());
    }
}