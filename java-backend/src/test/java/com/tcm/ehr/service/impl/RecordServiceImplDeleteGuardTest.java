package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 按条件删除的**防误删守卫**（批次 13 第一阶段的第一个测试）。
 *
 * <p>为什么先补它：`deleteByFilter` 是破坏性最强的入口，而它原先**一个测试都没有**
 * （批次 13 的覆盖表已随该批次完成归档，见 git 历史）。守卫恰好写在所有 mapper 调用之前，
 * 因此这些用例不需要任何映射替身 —— 没有替身，也就没有 flakiness。</p>
 *
 * <p>三条被锁住的规则：
 * <ol>
 * <li>没有筛选条件（null 或空对象）⇒ 直接拒绝，不能变成「删全库」；</li>
 * <li>时间区间<b>只给一端</b>不算条件 ⇒ 仍拒绝。这一条最容易写错：
 *     只给 start 会被理解成「从某时到最新」，那正是误删的口径；</li>
 * <li>拒绝的异常类型是 IllegalArgumentException，文案含「至少设置一个筛选条件」，
 *     前端据此提示，不能悄悄吞掉。</li>
 * </ol>
 * </p>
 */
class RecordServiceImplDeleteGuardTest {

    /** 只用到构造参数：守卫在 mapper 调用之前抛出，故这里传替身即可，不需要 baseMapper */
    private static RecordServiceImpl svc() {
        return new RecordServiceImpl(
                mock(tools.jackson.databind.ObjectMapper.class),
                mock(com.tcm.ehr.service.INlpBatchService.class),
                mock(com.tcm.ehr.mapper.ReviewTaskMapper.class),
                mock(com.tcm.ehr.service.IDictionaryTermStore.class));
    }

    @Test
    @DisplayName("无条件对象 ⇒ 拒绝，不能变成删全库")
    void rejectsNullFilters() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc().deleteByFilter(null));
        assertEquals("请至少设置一个筛选条件，避免误删全库", ex.getMessage());
    }

    @Test
    @DisplayName("空筛选对象 ⇒ 拒绝")
    void rejectsEmptyFilters() {
        assertThrows(IllegalArgumentException.class, () -> svc().deleteByFilter(new FiltersDTO()));
    }

    @Test
    @DisplayName("时间区间只给一端 ⇒ 不算条件，仍拒绝（这一条曾是最容易误删的口径）")
    void rejectsOneSidedDateRange() {
        FiltersDTO onlyStart = new FiltersDTO();
        onlyStart.setDateRange(List.of("2024-01-01", ""));
        assertThrows(IllegalArgumentException.class, () -> svc().deleteByFilter(onlyStart),
                "只给一端会被理解成「从某时到最新」，必须视为没有条件");

        FiltersDTO onlyEnd = new FiltersDTO();
        onlyEnd.setDateRange(List.of("", "2024-12-31"));
        assertThrows(IllegalArgumentException.class, () -> svc().deleteByFilter(onlyEnd));
    }

    @Test
    @DisplayName("有任一维度条件 ⇒ 守卫放行（随后才需要数据域上下文）")
    void passesGuardWhenAnyDimensionPresent() {
        // 有筛选条件 ⇒ 守卫不再拦，进入 RequestUtils 取数据域。
        // 本用例只证明「不再抛守卫异常」：不给请求上下文时会以别的方式失败，
        // 所以这里给出上下文，让失败点落在后面（真正的删库行为由后续阶段的测试覆盖）。
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            FiltersDTO f = new FiltersDTO();
            f.setGrade("甲");
            // baseMapper 未注入 ⇒ 会在取到数据域之后失败；关键是**不是**守卫的异常
            Exception ex = assertThrows(Exception.class, () -> svc().deleteByFilter(f));
            assertEquals(false, ex instanceof IllegalArgumentException,
                    "有筛选条件时不应再抛守卫异常，实际：" + ex);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
