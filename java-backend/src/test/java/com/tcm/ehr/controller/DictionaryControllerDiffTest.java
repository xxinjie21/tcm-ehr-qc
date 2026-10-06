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
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_USER_ID, USER_ID);
        req.setAttribute(RequestUtils.ATTR_USERNAME, username);
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
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