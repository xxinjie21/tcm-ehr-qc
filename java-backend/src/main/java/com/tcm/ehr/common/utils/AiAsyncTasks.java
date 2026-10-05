package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.AiReplyVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AI 长响应的异步任务登记（批次 15 · 15.1）。
 *
 * <p><b>为什么要有它</b>：解读 / 问答 / 复核预检原来都是「同步等 LLM」——
 * 一次生成要几十秒，那段时间里 Tomcat 的请求线程被占着。虽然连接池有 200 线程，
 * 但并发十几个长请求就会把线程吃光，其它接口跟着排队。改成「提交拿任务号 + 轮询结果」后，
 * 请求线程只做提交与查询，长响应跑在专用线程池里。</p>
 *
 * <p><b>形态选择</b>：计划文档允许「任务式接口或 SSE」，这里选**任务式**（提交 + 轮询）：
 * 少一层连接管理（SSE 要处理断线重连、代理缓冲），且天然支持「用户离开页面再回来」——
 * 轮询只要再查一次任务号即可，这正是 15.2 要的「可离开页面」。</p>
 *
 * <p><b>两个必须做对的地方</b>：</p>
 * <ol>
 * <li><b>请求上下文要显式传播</b>：AI 服务内部读 {@code RequestUtils.currentOrgId()}，
 *     而异步线程没有 {@code RequestContext}（与批任务 worker 同一个坑，代码里已有多处告诫）。
 *     所以提交时捕获 {@link RequestContextHolder} 的属性，在任务线程里重新绑好、结束再清掉。</li>
 * <li><b>结果按归属隔离</b>：任务号是随机的，但不能只靠「猜不到」——登记项记住提交者的
 *     org 与 user，查询时校验；否则一个用户拿到任务号就能读到别人的 AI 结论。</li>
 * </ol>
 *
 * <p>线程池与登记表都**有界**：池固定 4 线程（LLM 是 I/O 等待型，4 个足够且不会把下游压垮），
 * 登记表按条数与存活时间双重淘汰，避免长跑进程里无界增长。</p>
 */
@Slf4j
@Component
public class AiAsyncTasks {

    /** 任务状态：只有这三种，且都表示「有结论可读」或「还在跑」 */
    public enum State { RUNNING, DONE, FAILED }

    /** 一次异步任务的结果快照 */
    public record Snapshot(State state, AiReplyVO reply, String error) {
    }

    /** 登记项：状态 + 归属 + 结果 + 入库时刻 */
    private record Task(String orgId, String userId, State state, AiReplyVO reply, String error,
                        LocalDateTime createdAt) {
        Task with(State s, AiReplyVO r, String e) {
            return new Task(orgId, userId, s, r, e, createdAt);
        }
    }

    /** 单次 AI 生成最多跑多久：超过就判失败，避免任务永远停在 RUNNING */
    private static final long TASK_TIMEOUT_SECONDS = 120L;
    /** 结果保留多久：够用户离开页面再回来取即可，不做长期存储 */
    private static final long RESULT_TTL_SECONDS = 600L;
    /** 登记表上限：超出时按入库时刻淘汰最旧的，避免无界增长 */
    private static final int MAX_TASKS = 2000;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ai-async");
        t.setDaemon(true);
        return t;
    });

    /**
     * 提交一次 AI 生成，立即返回任务号。
     *
     * @param work 真正调 AI 服务的动作（在专用线程上执行）
     * @return 任务号；调用方拿它轮询
     */
    public String submit(Supplier<AiReplyVO> work) {
        prune();
        String id = UUID.randomUUID().toString();
        // 在**请求线程**上捕获上下文：异步线程里 RequestUtils 取不到当前组织
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        String orgId = RequestUtils.currentOrgId();
        String userId = safeUserId();
        tasks.put(id, new Task(orgId, userId, State.RUNNING, null, null, LocalDateTime.now()));

        pool.submit(() -> {
            RequestAttributes previous = RequestContextHolder.getRequestAttributes();
            try {
                if (attrs != null) {
                    RequestContextHolder.setRequestAttributes(attrs);
                }
                AiReplyVO reply = work.get();
                update(id, t -> t.with(State.DONE, reply, null));
            } catch (Exception e) {
                // 失败也要留痕：前端要给出「可重试 + 明确原因」，不能只显示转圈
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("[AI 异步] 任务 {} 失败：{}", id, msg);
                update(id, t -> t.with(State.FAILED, null, msg));
            } finally {
                if (attrs != null) {
                    RequestContextHolder.resetRequestAttributes();
                } else {
                    RequestContextHolder.setRequestAttributes(previous);
                }
            }
        });
        return id;
    }

    /**
     * 查任务结果。
     *
     * @return 任务的快照；任务不存在或不属于调用者返回 null（调用方一律按 404 处理，
     *         不区分「不存在」与「不是你的」—— 否则能被用来探测任务号是否有效）
     */
    public Snapshot get(String taskId) {
        Task t = taskId == null ? null : tasks.get(taskId);
        if (t == null) {
            return null;
        }
        if (!t.orgId().equals(RequestUtils.currentOrgId()) || !t.userId().equals(safeUserId())) {
            return null;
        }
        if (LocalDateTime.now().isAfter(t.createdAt().plusSeconds(TASK_TIMEOUT_SECONDS))
                && t.state() == State.RUNNING) {
            // 超时收敛：进程重启或线程被卡住时，RUNNING 不能永远挂着
            return new Snapshot(State.FAILED, null, "生成超时（超过 " + TASK_TIMEOUT_SECONDS + " 秒），请重试");
        }
        return new Snapshot(t.state(), t.reply(), t.error());
    }

    private void update(String id, java.util.function.UnaryOperator<Task> f) {
        tasks.computeIfPresent(id, (k, v) -> f.apply(v));
    }

    /** 淘汰过期与超量的登记项 */
    private void prune() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(RESULT_TTL_SECONDS);
        tasks.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(deadline));
        if (tasks.size() >= MAX_TASKS) {
            tasks.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e -> e.getValue().createdAt()))
                    .limit(Math.max(1, tasks.size() - MAX_TASKS + 1))
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(tasks::remove);
        }
    }

    /** 取当前用户号；取不到（如测试或非请求线程）给空串，归属校验仍按 org 生效 */
    private static String safeUserId() {
        try {
            return String.valueOf(RequestUtils.currentUserId());
        } catch (Exception e) {
            return "";
        }
    }

    /** 供测试与关停使用：等池子停下来 */
    public void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
