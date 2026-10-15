package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.AiReplyVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AI 异步任务的请求上下文传播（审查报告 H4 回归测试）。
 *
 * <p><b>为什么需要它</b>：H4 之前 {@code submit()} 把整个 {@code RequestAttributes}
 * 交给任务线程重绑 —— 该对象包装容器请求对象，请求结束后被容器回收清空，
 * 任务线程夹一次数据库往返后读 {@code RequestUtils.currentOrgId()} 恒为「未知」，
 * 于是 AI 解读/复核恒定报「病历不存在」、termsuggest 静默降级。修复后改为
 * **捕获值**（user / org / 两个角色标量）并在任务线程重建纯值上下文。</p>
 *
 * <p>本测试用「提交线程挂一个纯值上下文 → 任务线程 echo 它们 → 断言读数一致」证明
 * 传播语义成立，不依赖任何 LLM / 网络 / 容器。</p>
 */
class AiAsyncTasksTest {

    private static final String ORG = "org-h4-test";
    private static final String USER = "user-h4-test";
    private static final String ROLE = "管理员";
    private static final String ORG_ROLE = "owner";

    @BeforeEach
    void seedRequestContext() {
        // 模拟「请求线程」：与真实信息只差它不是容器对象，取值口径一致
        AiAsyncTasks.ValueRequestAttributes attrs = new AiAsyncTasks.ValueRequestAttributes();
        attrs.setAttribute(RequestUtils.ATTR_ORG_ID, ORG, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_USER_ID, USER, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_ROLE, ROLE, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_ORG_ROLE, ORG_ROLE, RequestAttributes.SCOPE_REQUEST);
        // 「不限组织」是 JwtInterceptor 在请求线程置的独立标记，任务线程读不到它
        attrs.setAttribute(RequestUtils.ATTR_VIEW_ALL_ORGS, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 任务线程读取到提交时捕获的组织用户与角色() throws Exception {
        AiAsyncTasks tasks = new AiAsyncTasks(executor());
        String taskId = tasks.submit(() -> {
            // 任务线程里逐项读值：H4 修复前这里是 uniform「unknown / 空」
            String echo = RequestUtils.currentOrgId() + "|"
                    + RequestUtils.currentUserId() + "|"
                    + RequestUtils.currentRole() + "|"
                    + RequestUtils.currentOrgRole() + "|"
                    + RequestUtils.viewAllOrgs();
            AiReplyVO vo = new AiReplyVO();
            vo.setAnswer(echo);
            return vo;
        });

        AsyncTaskRegistry.Snapshot<AiReplyVO> snap = await(tasks, taskId, Duration.ofSeconds(10));
        assertNotNull(snap, "任务应能在 10s 内出结果");
        assertEquals(AsyncTaskRegistry.State.DONE, snap.state(), "任务应成功完成：" + snap.error());
        assertEquals(ORG + "|" + USER + "|" + ROLE + "|" + ORG_ROLE + "|true",
                snap.reply().getAnswer(),
                "H4：异步线程必须读到提交时刻捕获的上下文值，而不是「未知」");
    }

    /**
     * 「不限组织」同样要传播。
     *
     * <p>它只在请求线程由 {@code JwtInterceptor} 置一次，任务线程是另一根线程，
     * 漏传就等于 false：管理员发起的异步 AI 解读 / 复核会被按他所属的那一个组织过滤，
     * 对其它组织的病历恒返回「病历不存在」。</p>
     */
    @Test
    void 提交线程未置viewAll标记时任务线程读到false() throws Exception {
        AiAsyncTasks.ValueRequestAttributes attrs = new AiAsyncTasks.ValueRequestAttributes();
        attrs.setAttribute(RequestUtils.ATTR_ORG_ID, ORG, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_USER_ID, USER, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_ROLE, ROLE, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_ORG_ROLE, ORG_ROLE, RequestAttributes.SCOPE_REQUEST);
        RequestContextHolder.setRequestAttributes(attrs);

        AiAsyncTasks tasks = new AiAsyncTasks(executor());
        String taskId = tasks.submit(() -> {
            AiReplyVO vo = new AiReplyVO();
            vo.setAnswer(String.valueOf(RequestUtils.viewAllOrgs()));
            return vo;
        });

        AsyncTaskRegistry.Snapshot<AiReplyVO> snap = await(tasks, taskId, Duration.ofSeconds(10));
        assertNotNull(snap, "任务应能在 10s 内出结果");
        assertEquals(AsyncTaskRegistry.State.DONE, snap.state(), "任务应成功完成：" + snap.error());
        assertEquals("false", snap.reply().getAnswer(),
                "提交线程没有「看全部」标记时，任务线程也必须读到 false（fail-closed，不能反向放大）");
    }

    @Test
    void 提交线程无请求上下文也安全回落() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        AiAsyncTasks tasks = new AiAsyncTasks(executor());
        String taskId = tasks.submit(() -> {
            AiReplyVO vo = new AiReplyVO();
            vo.setSource("done");
            return vo;
        });

        AsyncTaskRegistry.Snapshot<AiReplyVO> snap = await(tasks, taskId, Duration.ofSeconds(10));
        assertNotNull(snap, "无请求上下文时提交也应正常完成任务");
        assertEquals(AsyncTaskRegistry.State.DONE, snap.state(), "任务应成功完成：" + snap.error());
    }

    /** 真实可跑的 Spring 线程池（替代原先基类里手写的池）：2 线程足够本测试的用例 */
    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(2);
        e.setQueueCapacity(16);
        e.initialize();
        return e;
    }

    /** 轮询到终态（RUNNING / DONE / FAILED 之外即出）或超时 */
    private static AsyncTaskRegistry.Snapshot<AiReplyVO> await(AiAsyncTasks tasks, String id,
                                                               Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        AsyncTaskRegistry.Snapshot<AiReplyVO> snap;
        do {
            snap = tasks.get(id);
            if (snap != null && snap.state() != AsyncTaskRegistry.State.RUNNING) {
                return snap;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return snap;
    }

    @Test
    void 另一实例能通过共享层读到本实例提交的任务() throws Exception {
        // 两个实例共用同一个「Redis」：模拟多实例部署（R14 / AI-28）。
        // 改造前登记表只在进程内，B 实例轮询必然得到 null（前端看到「生成失败」）。
        Map<String, String> store = new ConcurrentHashMap<>();
        tools.jackson.databind.json.JsonMapper mapper =
                tools.jackson.databind.json.JsonMapper.builder().build();

        AiAsyncTasks a = new AiAsyncTasks(executor());
        a.setRedis(mockRedis(store));
        a.setObjectMapper(mapper);

        String taskId = a.submit(() -> {
            AiReplyVO vo = new AiReplyVO();
            vo.setAnswer("A 实例算出来的");
            return vo;
        });
        AsyncTaskRegistry.Snapshot<AiReplyVO> fromA = await(a, taskId, Duration.ofSeconds(10));
        assertNotNull(fromA, "本实例应能在 10s 内出结果");
        assertEquals(AsyncTaskRegistry.State.DONE, fromA.state(), "本实例应拿到成功结果：" + fromA.error());

        // B 实例的进程内表是空的。真实前端也是轮询，这里同样等到终态
        AiAsyncTasks b = new AiAsyncTasks(executor());
        b.setRedis(mockRedis(store));
        b.setObjectMapper(mapper);

        AsyncTaskRegistry.Snapshot<AiReplyVO> fromB = await(b, taskId, Duration.ofSeconds(10));
        assertNotNull(fromB, "R14：另一实例必须能从共享层读到任务，而不是当作不存在（404）");
        assertEquals(AsyncTaskRegistry.State.DONE, fromB.state(), "共享层里的状态应已收敛为 DONE");
        assertEquals("A 实例算出来的", fromB.reply().getAnswer(), "结果内容应跨实例一致");
    }

    /** 用 Map 充当 Redis 键空间；get / set(TTL) 落在同一个命名空间里 */
    private static StringRedisTemplate mockRedis(Map<String, String> store) {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(ops);
        Mockito.when(ops.get(Mockito.anyString()))
                .thenAnswer(inv -> store.get(inv.getArgument(0)));
        Mockito.doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(Mockito.anyString(), Mockito.anyString(), Mockito.any(Duration.class));
        return redis;
    }
}