package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.cache.StatsCache;
import com.tcm.ehr.common.cache.StatsCacheInvalidator;
import com.tcm.ehr.service.IStandardizationReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 报告预热的触发路径（2026-10-28）。
 *
 * <p>要守住的是「预热挂在失效这一个口子上」这条结构：任何写操作都经由
 * {@link StatsCacheInvalidator#invalidateStats()} 失效，预热必须是它的副作用，
 * 而不是靠 9 个写方法各自记得再调一次 —— 漏一处就是该路径永远回到 2.3s 冷算。</p>
 *
 * <p>同时守住两处边界：{@code auth.admin-can-view-data=false} 时不预热
 * （没有「看全部」视角，算了也没人读），以及预热失败绝不外抛
 * （它跑在写请求线程的钩子里，抛出去就是写操作失败）。</p>
 *
 * <p>为了确定性，用例把执行器换成 {@code Runnable::run}（同步执行），
 * 否则断言会和后台线程赛跑。</p>
 */
@DisplayName("ReportWarmup：启动/写后预热")
class ReportWarmupTest {

    private IStandardizationReportService reportService;
    private StatsCache mockCache;

    @BeforeEach
    void setUp() {
        reportService = mock(IStandardizationReportService.class);
        // invalidateStats() 在静态 cache 为 null 时会提前 return（单测里从未装配过 Spring），
        // 故这里显式注入一个 mock，让失效路径真的走到预热钩子。
        mockCache = mock(StatsCache.class);
        new StatsCacheInvalidator(mockCache);
    }

    @AfterEach
    void tearDown() {
        // 静态钩子是进程级的：不清掉会污染同一 JVM 里的其它测试
        StatsCacheInvalidator.setWarmupHook(null);
    }

    private ReportWarmup warmup(boolean adminCanViewData) {
        ReportWarmup w = new ReportWarmup(reportService, adminCanViewData);
        w.setExecutorForTest(Runnable::run);
        return w;
    }

    @Test
    @DisplayName("启动完成 → 预热一次（用户打开报告页时已是暖的）")
    void onReadyWarmsUp() {
        ReportWarmup w = warmup(true);

        w.onReady();

        verify(reportService).warmUpAllView();
    }

    @Test
    @DisplayName("写操作失效 → 预热随失效自动触发（不依赖各写方法记得再调一次）")
    void invalidationTriggersWarmUp() {
        ReportWarmup w = warmup(true);
        w.register();

        StatsCacheInvalidator.invalidateStats();

        verify(mockCache).invalidateForWrite();
        verify(reportService).warmUpAllView();
    }

    @Test
    @DisplayName("admin-can-view-data=false → 不预热：不存在「看全部」视角，算了没人读")
    void disabledSwitchSkipsWarmUp() {
        ReportWarmup w = warmup(false);
        w.register();

        w.onReady();
        StatsCacheInvalidator.invalidateStats();

        // 失效照常发生，只是不预热
        verify(mockCache).invalidateForWrite();
        verify(reportService, never()).warmUpAllView();
    }

    @Test
    @DisplayName("预热的异常不外抛：它挂在写请求线程上，抛出去就变成写操作失败")
    void warmUpFailureIsSwallowed() {
        doThrow(new RuntimeException("MySQL 超时")).when(reportService).warmUpAllView();
        ReportWarmup w = warmup(true);
        w.register();

        assertDoesNotThrow(() -> StatsCacheInvalidator.invalidateStats(),
                "预热失败最多让下次读数再冷算一次，绝不能连累写入");
        assertDoesNotThrow(w::onReady);
        verify(reportService, times(2)).warmUpAllView();
    }

    @Test
    @DisplayName("关闭时注销钩子：之后的失效不再触发预热（避免关停期往已关的池里丢任务）")
    void shutdownUnregistersHook() {
        ReportWarmup w = warmup(true);
        w.register();
        w.shutdown();

        StatsCacheInvalidator.invalidateStats();

        verify(mockCache).invalidateForWrite();
        verify(reportService, never()).warmUpAllView();
    }
}
