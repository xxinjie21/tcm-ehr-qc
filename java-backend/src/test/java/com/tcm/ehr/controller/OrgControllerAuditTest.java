package com.tcm.ehr.controller;

import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.domain.dto.OrgDTOs;
import com.tcm.ehr.service.IOrgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 28.2：组织 / 权限的三条写路径必须各留一条审计日志。
 *
 * <p>此前它们一条都没有：全后端 6 个写入方不含移除成员 / 转让所有权 / 权限变更，
 * 于是「谁在什么时候把谁的写权限收了」在审计页查不到 —— 这正是审计存在的意义。</p>
 *
 * <p>本测试只钉住「有日志、且日志能定位到组织与成员」；措辞不钉死，
 * 免得改一个提示词就红。</p>
 */
class OrgControllerAuditTest {

    private IOrgService orgService;
    private OperationLogger operationLogger;
    private OrgController controller;

    @BeforeEach
    void setUp() {
        orgService = mock(IOrgService.class);
        operationLogger = mock(OperationLogger.class);
        controller = new OrgController(orgService, operationLogger);
    }

    @Test
    @DisplayName("移除成员留审计日志（28.2）")
    void removeMemberWritesAuditLog() {
        controller.removeMember("org-A", "u-2");

        verify(orgService).removeMember("org-A", "u-2");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogger).log(eq("移除成员"), eq("org-A"), detail.capture());
        assertTrue(detail.getValue().contains("u-2"),
                "日志必须能定位到被移除的成员，实际：" + detail.getValue());
    }

    @Test
    @DisplayName("转让所有权留审计日志（28.2）")
    void transferOwnerWritesAuditLog() {
        OrgDTOs.TransferOwnerRequest body = new OrgDTOs.TransferOwnerRequest();
        body.setNewOwnerUserId("u-9");

        controller.transferOwner("org-A", "u-2", body);

        verify(orgService).transferOwner("org-A", "u-9");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogger).log(eq("转让所有权"), eq("org-A"), detail.capture());
        assertTrue(detail.getValue().contains("u-9"),
                "日志必须记下新所有者，实际：" + detail.getValue());
    }

    @Test
    @DisplayName("权限变更留审计日志，且区分「改成关」与「这一位不改」（28.2）")
    void setPermissionsWritesAuditLog() {
        OrgDTOs.SetPermissionsRequest body = new OrgDTOs.SetPermissionsRequest();
        body.setCanWriteDictionary(true);
        body.setCanWriteQcRules(null);   // null = 这一位不改

        controller.setPermissions("org-A", "u-3", body);

        verify(orgService).setPermissions("org-A", "u-3", true, null);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(operationLogger).log(eq("权限变更"), eq("org-A"), detail.capture());
        String text = detail.getValue();
        assertTrue(text.contains("u-3"), "日志必须能定位到成员，实际：" + text);
        assertTrue(text.contains("开"), "授予的开关要记成开，实际：" + text);
        assertTrue(text.contains("不变"), "null 是「不改」而不是「关」，实际：" + text);
    }
}
