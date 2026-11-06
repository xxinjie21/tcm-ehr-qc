package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.cache.StatsCacheInvalidator;
import com.tcm.ehr.service.IStandardizationReportService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 标准化报告预热器（2026-10-28）。
 *
 * <p>要解决的事：报告冷算约 2.3s（全量取 122MB JSON + 解析 + 聚合），而它<b>只在两种时刻发生</b>
 * —— 进程刚起、写入后第一次读。这两种时刻都不该让用户站着等：数据就绪或刚写完时先算一遍，
 * 等用户真的打开报告页，命中的是已经回填好的缓存。</p>
 *
 * <p>为什么挂在 {@link StatsCacheInvalidator} 上而不是散在 9 个写方法里：预热与失效必须同源
 * —— 失效了却没预热 = 下一个读者照旧阻塞 2.3s；预热了却没失效 = 旧版本号里写新值，
 * 比不预热更糟。挂在这一个口子上，「失效即可疑、可疑即预热」是结构性成立的。</p>
 *
 * <p>边界（刻意不做的事）：</p>
 * <ul>
 *   <li><b>只预热「看全部」视角</b>：组内成员的 key 带自己的组标识，替他们预热一旦口径搞错就是
 *       跨组织泄露（见 {@link #adminCanViewData} 与
 *       {@link IStandardizationReportService#warmUpAllView()}）。</li>
 *   <li><b>只预热默认范围</b>：带日期/科室的筛选组合无界，预热的收益抵不上算力；
 *       这类请求的首次读仍是冷算，如实接受。</li>
 *   <li><b>有界 + 丢弃</b>：单线程、队列容量 1、满了直接丢 —— 一次导入可能连续触发多次失效，
 *       不允许预热任务堆起来抢数据库。</li>
 * </ul>
 */
@Slf4j
@Component
public class ReportWarmup {

    private final IStandardizationReportService reportService;

    /**
     * {@code auth.admin-can-view-data=false} 时**不存在**「看全部」视角（管理员也被限在本组），
     * 预热出来的 key 没有任何真实请求会读 —— 等于每次写入白扫一遍 3 万行，
     * 故这种情况下干脆不预热（与 {@code JwtInterceptor} 用同一个开关，口径一致）。
     */
    private final boolean adminCanViewData;

    /**
     * 预热线程池：1 个守护线程 + 容量 1 的队列 + {@link ThreadPoolExecutor.DiscardPolicy}。
     *
     * <p>与 {@code StatsCache} 的后台重算池同一套取舍：算得贵，宁可丢一次刷新机会，
     * 也不能因为写入密集就把任务堆起来（丢掉的只是「这次提前算」，下次读仍会现算）。</p>
     */
    private final ExecutorService pool = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "report-warmup");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** 实际提交预热的执行器；非 final 仅为测试可注入同步执行器（见 {@link #setExecutorForTest}） */
    private Executor executor = pool;

    public ReportWarmup(IStandardizationReportService reportService,
                        @Value("${auth.admin-can-view-data:true}") boolean adminCanViewData) {
        this.reportService = reportService;
        this.adminCanViewData = adminCanViewData;
    }

    /** 启动时登记写后预热钩子（钩子是静态门面，见 StatsCacheInvalidator 的类注释） */
    @PostConstruct
    void register() {
        if (!adminCanViewData) {
            log.info("[报告预热] auth.admin-can-view-data=false：无「看全部」视角，不预热");
            return;
        }
        StatsCacheInvalidator.setWarmupHook(this::requestWarmUp);
    }

    @PreDestroy
    void shutdown() {
        StatsCacheInvalidator.setWarmupHook(null);
        pool.shutdownNow();
    }

    /**
     * 启动完成后预热：此刻 Flyway、词典装载、ES mapping 都已就绪，且不占用启动时间。
     *
     * <p>为什么不在 {@code @PostConstruct}：那时数据源/迁移可能还没跑完，
     * 抢跑会算出半张表的结果并回填进缓存。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        requestWarmUp();
    }

    /**
     * 提交一次预热（启动后 / 写后都走这里）：**永不阻塞、永不抛异常**。
     *
     * <p>队列满或池已关闭时静默跳过 —— 用户下次读报告仍会现算，只是没提前算。</p>
     */
    void requestWarmUp() {
        if (!adminCanViewData) {
            return;
        }
        try {
            executor.execute(this::warmUp);
        } catch (Exception e) {
            // 1. DiscardPolicy 走的是「静默丢弃」，这里兜的是池已关闭 / 自定义执行器拒绝
            log.debug("[报告预热] 本次提交被拒（队列满或已关闭），跳过: {}", e.getMessage());
        }
    }

    private void warmUp() {
        long t0 = System.nanoTime();
        try {
            reportService.warmUpAllView();
            log.info("[报告预热] 完成 {} ms（看全部视角 / 默认范围）",
                    (System.nanoTime() - t0) / 1_000_000);
        } catch (Exception e) {
            // 1. 预热失败不影响任何请求：缓存没写进去，下一个读者照常阻塞现算
            log.warn("[报告预热] 失败（读路径不受影响，下次读会现算）: {}", e.getMessage());
        }
    }

    /** 测试用：替换执行器（{@code Runnable::run} 可让断言确定性） */
    void setExecutorForTest(Executor executor) {
        this.executor = executor;
    }
}
