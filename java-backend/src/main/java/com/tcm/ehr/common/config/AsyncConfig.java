package com.tcm.ehr.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行的线程池：统一交给 Spring 框架（{@link ThreadPoolTaskExecutor}），
 * 不再各处手写 Executors.newFixedThreadPool / {@code new ThreadPoolExecutor}。
 *
 * <ul>
 *   <li>aiAsyncExecutor / nlpAsyncExecutor：AI、NLP 单条长响应的异步任务池。
 *       4 线程 + 有界队列 100、满载即拒（AbortPolicy）—— 让「池满」变成前端可见的 503
 *       而不是静默排队，且 150s 后前端已放弃时任务不会还堆在无界队列里。</li>
 *   <li>qcBatchExecutor / nlpBatchExecutor：批量任务的「认领任务」常驻 worker 池。</li>
 *   <li>nlpRecordExecutor：NLP 单个任务内并行处理病历的池（与认领池分开，否则占着线程等
 *       页内结果会死锁）。</li>
 * </ul>
 *
 * 所有池都用守护线程：进程退出不被非守护线程吊住。批量池的大小与属性取值一致，
 * 认领 worker 数 = qc.batch.concurrency / nlp.batch.concurrency。
 *
 * <b>关停策略：刻意不等在跑任务</b>（不设 setWaitForTasksToCompleteOnShutdown /
 * setAwaitTerminationSeconds，两者默认 false）。在跑的可能是 150s 级的长响应，
 * 等它跑完会让关停迟迟不返回；宁可丢弃（前端会收到连接中断，可重试）也不阻塞关停。
 * 这是取舍、不是遗漏 —— 见《common组件审查报告》§4.9。
 *
 * <b>线程命名与守护位交给 Spring 原生</b>（《common组件审查报告》§1.2）：不再自写
 * ThreadFactory + AtomicInteger。{@link ThreadPoolTaskExecutor} 未显式设
 * ThreadFactory 时<b>以自身为工厂</b>，而它继承 CustomizableThreadFactory →
 * CustomizableThreadCreator，故 setThreadNamePrefix / setDaemon
 * 直接生效，线程名仍是 {@code <prefix><序号>}（序号从 1 起），与原先逐字一致。
 */
@Configuration
public class AsyncConfig {

    /**
     * AI 单条异步任务池：固定 4 线程（下游是 I/O 等待型，4 个足够且不会把下游压垮）、
     * 有界队列 100，满载即拒。
     */
    @Bean
    public ThreadPoolTaskExecutor aiAsyncExecutor() {
        return boundedAsyncPool("ai-task-");
    }

    /** NLP 单条异步任务池：与 AI 分开，避免两边互相挤占。 */
    @Bean
    public ThreadPoolTaskExecutor nlpAsyncExecutor() {
        return boundedAsyncPool("nlp-task-");
    }

    private static ThreadPoolTaskExecutor boundedAsyncPool(String prefix) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(4);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix(prefix);
        e.setDaemon(true);
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return e;
    }

    /** QC 批量重算的常驻 worker 池，大小 = qc.batch.concurrency */
    @Bean
    public ThreadPoolTaskExecutor qcBatchExecutor(@Value("${qc.batch.concurrency:2}") int concurrency) {
        return fixedPool(concurrency, "qc-batch-worker-");
    }

    /**
     * 数据清洗任务的常驻 worker 池，大小 = clean.batch.concurrency（默认 1）。
     *
     * <b>必须独立成池，不能复用 qcBatchExecutor</b>：批量 worker 是
     * {@code while(running)} 的常驻循环，会<b>永久占满</b>它所在池的全部线程；
     * 池大小本就是「常驻 worker 数」。若清洗 worker 也 submit 到质控池，
     * 它只会排在队列里<b>永不执行</b>（表现为任务永远停在 QUEUED）。
     */
    @Bean
    public ThreadPoolTaskExecutor cleanBatchExecutor(@Value("${clean.batch.concurrency:1}") int concurrency) {
        return fixedPool(concurrency, "clean-batch-worker-");
    }

    /** NLP 批量解析的常驻 worker 池（认领任务），大小 = nlp.batch.concurrency */
    @Bean
    public ThreadPoolTaskExecutor nlpBatchExecutor(@Value("${nlp.batch.concurrency:8}") int concurrency) {
        return fixedPool(concurrency, "nlp-batch-worker-");
    }

    /** NLP 任务内并行处理病历的池，大小同上，两个池共享同一份并发预算 */
    @Bean
    public ThreadPoolTaskExecutor nlpRecordExecutor(@Value("${nlp.batch.concurrency:8}") int concurrency) {
        return fixedPool(concurrency, "nlp-record-worker-");
    }

    /** 固定大小池：core=max，队列无界（与 newFixedThreadPool 语义一致） */
    private static ThreadPoolTaskExecutor fixedPool(int concurrency, String prefix) {
        int threads = Math.max(1, concurrency);
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(threads);
        e.setMaxPoolSize(threads);
        e.setThreadNamePrefix(prefix);
        e.setDaemon(true);
        return e;
    }
}
