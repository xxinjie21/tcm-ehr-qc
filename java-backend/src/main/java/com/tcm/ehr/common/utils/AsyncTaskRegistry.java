package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 长耗时任务的异步登记基类（提交拿任务号 + 轮询结果）。
 *
 * <p><b>为什么要有它</b>：解读 / 问答 / 复核预检 / 单条 NLP 抽取原来都是「同步等下游」——
 * 一次生成要几十秒，那段时间里 Tomcat 的请求线程被占着。虽然连接池有 200 线程，
 * 但并发十几个长请求就会把线程吃光，其它接口跟着排队。改成「提交拿任务号 + 轮询结果」后，
 * 请求线程只做提交与查询，长响应跑在专用线程池里。</p>
 *
 * <p><b>形态选择</b>：计划文档允许「任务式接口或 SSE」，这里选**任务式**（提交 + 轮询）：
 * 少一层连接管理（SSE 要处理断线重连、代理缓冲），且天然支持「用户离开页面再回来」——
 * 轮询只要再查一次任务号即可。</p>
 *
 * <p><b>三个必须做对的地方</b>：</p>
 * <ol>
 * <li><b>请求上下文要显式传播</b>：任务体内部会读 {@code RequestUtils.currentOrgId()}，
 *     而异步线程没有 RequestContext（与批任务 worker 同一个坑，代码里已有多处告诫）。
 *     所以提交时捕获用户 / 组织 / 角色**标量**，在任务线程里重建纯值上下文、结束再清掉。</li>
 * <li><b>结果按归属隔离</b>：任务号是随机的，但不能只靠「猜不到」——登记项记住提交者的
 *     org 与 user，查询时校验；否则一个用户拿到任务号就能读到别人的结论。</li>
 * <li><b>状态必须跨实例可见</b>（R14 / AI-28）：登记表若只在进程内，多实例部署下
 *     A 实例提交、B 实例轮询会查不到任务 → 404，前端看到「生成失败」而不是「还在跑」。
 *     故状态落共享层（Redis，{@code <前缀>{taskId}}，TTL 600s），进程内表退化为**一级缓存**。</li>
 * </ol>
 *
 * <p>线程池与登记表都**有界**：池是 Spring 的 {@link ThreadPoolTaskExecutor}（有界队列、满载即拒，
 * 由 {@link #submit} 回 503 而不是静默排队），登记表按条数与存活时间双重淘汰。
 * 共享层缺失（未装配 Redis）时自动退化为纯进程内行为，功能不降级为「不可用」。</p>
 *
 * <p><b>子类只提供两件事</b>：共享层 key 前缀与结果载荷类型（用于跨实例反序列化）。</p>
 *
 * @param <T> 任务结果载荷类型
 */
@Slf4j
public abstract class AsyncTaskRegistry<T> {

    /** 任务状态：只有这三种，且都表示「有结论可读」或「还在跑」 */
    public enum State { RUNNING, DONE, FAILED }

    /** 一次异步任务的结果快照 */
    public record Snapshot<T>(State state, T reply, String error) {
    }

    /** 登记项：状态 + 归属 + 结果 + 入库时刻（毫秒时间戳，便于跨实例序列化） */
    private record Task<T>(String orgId, String userId, State state, T reply, String error,
                           long createdAtMs) {
        Task<T> with(State s, T r, String e) {
            return new Task<>(orgId, userId, s, r, e, createdAtMs);
        }
    }

    /**
     * 共享层（Redis）的持久形态，与 {@link Task} 一一对应。
     *
     * <p>结果载荷存成 <b>JSON 字符串</b>而非内联对象：反序列化需要知道具体类型，
     * 而泛型在运行期被擦除 —— 由子类给出的 {@link #payloadType()} 决定读回什么类型。</p>
     *
     * <p>时间用毫秒时间戳而非 LocalDateTime：共享层不依赖调用方的时区与
     * JavaTime 序列化配置，少一处跨实例不一致的来源。</p>
     */
    private record TaskJson(String orgId, String userId, String state, String payloadJson,
                            String error, long createdAtMs) {
    }

    /** 单次任务最多跑多久：超过就判失败，避免任务永远停在 RUNNING */
    private static final long TASK_TIMEOUT_SECONDS = 120L;
    /** 结果保留多久：够用户离开页面再回来取即可，不做长期存储 */
    private static final long RESULT_TTL_SECONDS = 600L;
    /** 登记表上限：超出时按入库时刻淘汰最旧的，避免无界增长 */
    private static final int MAX_TASKS = 2000;

    /** 共享层 key 前缀，如 {@code task:ai:} / {@code task:nlp:} */
    private final String redisKeyPrefix;
    /** 结果载荷类型，供跨实例反序列化 */
    private final Class<T> payloadType;
    /**
     * 任务线程池：由 Spring 装配（见 AsyncConfig），有界队列 + AbortPolicy。
     *
     * <p>满载时由 {@link #submit} 转成 503 —— 若用无界队列则提交永不拒绝，前端 150s 放弃后
     * 任务还堆在队列里空转。</p>
     */
    private final ThreadPoolTaskExecutor executor;

    private final Map<String, Task<T>> tasks = new ConcurrentHashMap<>();

    /** 共享层（R14/AI-28）。为 null = 未装配（单测 / 未启用 Redis）→ 退化为纯进程内 */
    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;

    protected AsyncTaskRegistry(String redisKeyPrefix, Class<T> payloadType,
                                ThreadPoolTaskExecutor executor) {
        this.redisKeyPrefix = redisKeyPrefix;
        this.payloadType = payloadType;
        this.executor = executor;
    }

    /**
     * 可选装配共享层：Redis 与 ObjectMapper 都在时才启用。
     *
     * <p>刻意用 {@code required = false}：本类被单测直接 {@code new} 出来跑请求上下文传播，
     * 也允许在未启用 Redis 的环境里退化为纯进程内（与改造前行为一致），
     * 不让「共享层缺失」变成「功能不可用」。</p>
     */
    @Autowired(required = false)
    public void setRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Autowired(required = false)
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 提交一次任务，立即返回任务号。
     *
     * @param work 真正耗时的动作（在专用线程上执行）
     * @return 任务号；调用方拿它轮询
     * @throws BusinessException 任务池与队列都满（HTTP 503）
     */
    public String submit(Supplier<T> work) {
        prune();
        String id = UUID.randomUUID().toString();
        // **捕获值，而不是捕获请求上下文对象**。
        // 原实现把 RequestContextHolder 的整个 RequestAttributes 交给任务线程重绑，
        // 而该对象包装的是容器的请求对象 —— 请求结束后容器会回收并清空它；
        // 任务线程只要在提交后夹一次数据库往返就必然读到「未知」，
        // 于是 AI 解读/复核恒定报「病历不存在」、术语建议约三成静默降级。
        // 正确写法（与 QcBatchServiceImpl 的批任务同一口径）：把本条请求会用到的
        // 用户 / 组织 / 角色 / viewAll**标量**在提交线程固定下来，任务线程重建一个纯值上下文。
        String orgId = RequestUtils.currentOrgId();
        String userId = safeUserId();
        // 角色有两个维度：系统角色（管理员/用户）与组织角色（owner/member），
        // 任务线程读哪一个都不能退化成 null/unknown —— 一并捕获并在任务线程重建。
        // 无请求上下文时 RequestUtils.attr() 内部已整体兜住并返回 "unknown"，故直接用非 safe 版
        // （原 safeCurrentRole/safeCurrentOrgRole 的 catch 永不触发、返回值恒等，已删；审查报告 §2.5）
        String role = RequestUtils.currentRole();
        String orgRole = RequestUtils.currentOrgRole();
        // 「不限组织」也必须捕获：它只在请求线程由 JwtInterceptor 置一次（见
        // RequestUtils.ATTR_VIEW_ALL_ORGS），任务线程是另一根线程，读不到就等于 false。
        // 漏了它，管理员发起的异步 AI 解读 / 复核会被按他所属的那一个组织过滤，
        // 对其它组织的病历恒返回「病历不存在」（多组织部署下才暴露）。
        boolean viewAll = RequestUtils.viewAllOrgs();
        ValueRequestAttributes ctx = new ValueRequestAttributes();
        ctx.setAttribute(RequestUtils.ATTR_ORG_ID, orgId, RequestAttributes.SCOPE_REQUEST);
        ctx.setAttribute(RequestUtils.ATTR_USER_ID, userId, RequestAttributes.SCOPE_REQUEST);
        ctx.setAttribute(RequestUtils.ATTR_ROLE, role, RequestAttributes.SCOPE_REQUEST);
        ctx.setAttribute(RequestUtils.ATTR_ORG_ROLE, orgRole, RequestAttributes.SCOPE_REQUEST);
        ctx.setAttribute(RequestUtils.ATTR_VIEW_ALL_ORGS, viewAll, RequestAttributes.SCOPE_REQUEST);
        Task<T> running = new Task<>(orgId, userId, State.RUNNING, null, null,
                System.currentTimeMillis());
        tasks.put(id, running);
        // RUNNING 也要进共享层：否则另一实例在任务跑完前轮询会得到「不存在」而不是「还在跑」
        sharedPut(id, running);

        try {
            executor.execute(() -> {
                try {
                    RequestContextHolder.setRequestAttributes(ctx);
                    T reply = work.get();
                    update(id, t -> t.with(State.DONE, reply, null));
                } catch (Exception e) {
                    // 失败也要留痕：前端要给出「可重试 + 明确原因」，不能只显示转圈
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    log.warn("[异步任务] {} 失败：{}", id, msg);
                    update(id, t -> t.with(State.FAILED, null, msg));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            });
        } catch (RejectedExecutionException rejected) {
            // 队列已满（AI-2）：撤回刚登记的 RUNNING 项，否则前端轮询会永远看到「还在跑」
            tasks.remove(id);
            sharedDelete(id);
            log.warn("[异步任务] 任务池已满，拒绝任务 {}", id);
            throw new BusinessException(1012, "AI 繁忙，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return id;
    }

    /**
     * 查任务结果。
     *
     * @return 任务的快照；任务不存在或不属于调用者返回 null（调用方一律按 404 处理，
     *         不区分「不存在」与「不是你的」—— 否则能被用来探测任务号是否有效）
     */
    public Snapshot<T> get(String taskId) {
        if (taskId == null) {
            return null;
        }
        Task<T> t = tasks.get(taskId);
        if (t == null) {
            // 本地没有 → 可能是**另一个实例**提交的（R14/AI-28）。去共享层取一次，命中则回填一级缓存，
            // 后续轮询不再打 Redis。
            t = sharedGet(taskId);
            if (t != null) {
                tasks.put(taskId, t);
            }
        }
        if (t == null) {
            return null;
        }
        if (!t.orgId().equals(RequestUtils.currentOrgId()) || !t.userId().equals(safeUserId())) {
            return null;
        }
        if (System.currentTimeMillis() - t.createdAtMs() > TASK_TIMEOUT_SECONDS * 1000
                && t.state() == State.RUNNING) {
            // 超时收敛：进程重启或线程被卡住时，RUNNING 不能永远挂着
            return new Snapshot<>(State.FAILED, null,
                    "生成超时（超过 " + TASK_TIMEOUT_SECONDS + " 秒），请重试");
        }
        return new Snapshot<>(t.state(), t.reply(), t.error());
    }

    private void update(String id, UnaryOperator<Task<T>> f) {
        Task<T> cur = tasks.get(id);
        if (cur == null) {
            // 登记项已被淘汰（或本实例重启过）：无从重建归属信息，保持原语义不做补偿
            return;
        }
        Task<T> next = f.apply(cur);
        // 先写共享层、再更新一级缓存：让「另一实例读到的状态」永不落后于本实例，
        // 否则会出现「本实例已 DONE、别的实例仍读到 RUNNING」的无谓抖动。
        sharedPut(id, next);
        tasks.put(id, next);
    }

    // ------------------------------------------------------------ 共享层（R14/AI-28）

    private boolean sharedEnabled() {
        return redis != null && objectMapper != null;
    }

    /** 写共享层。失败只记警告：本实例内仍可读，不让共享层故障影响功能 */
    private void sharedPut(String id, Task<T> t) {
        if (!sharedEnabled()) {
            return;
        }
        try {
            String payloadJson = t.reply() == null ? null : objectMapper.writeValueAsString(t.reply());
            TaskJson json = new TaskJson(t.orgId(), t.userId(), t.state().name(), payloadJson,
                    t.error(), t.createdAtMs());
            redis.opsForValue().set(redisKeyPrefix + id, objectMapper.writeValueAsString(json),
                    Duration.ofSeconds(RESULT_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("[异步任务] {} 写共享层失败，本实例内仍可见: {}", id, e.getMessage());
        }
    }

    /** 读共享层。任何异常一律按「不存在」处理 —— 与归属校验同一个出口，不泄露差异 */
    private Task<T> sharedGet(String id) {
        if (!sharedEnabled()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(redisKeyPrefix + id);
            if (json == null) {
                return null;
            }
            TaskJson j = objectMapper.readValue(json, TaskJson.class);
            T payload = j.payloadJson() == null
                    ? null
                    : objectMapper.readValue(j.payloadJson(), payloadType);
            return new Task<>(j.orgId(), j.userId(), State.valueOf(j.state()), payload, j.error(),
                    j.createdAtMs());
        } catch (Exception e) {
            log.warn("[异步任务] {} 读共享层失败，按不存在处理: {}", id, e.getMessage());
            return null;
        }
    }

    /** 删除共享层登记（提交被拒时撤回，避免留下永远 RUNNING 的孤儿）。失败只记警告 */
    private void sharedDelete(String id) {
        if (!sharedEnabled()) {
            return;
        }
        try {
            redis.delete(redisKeyPrefix + id);
        } catch (Exception e) {
            log.warn("[异步任务] {} 清共享层失败: {}", id, e.getMessage());
        }
    }

    /** 淘汰过期与超量的登记项 */
    private void prune() {
        long deadline = System.currentTimeMillis() - RESULT_TTL_SECONDS * 1000;
        tasks.entrySet().removeIf(e -> e.getValue().createdAtMs() < deadline);
        if (tasks.size() >= MAX_TASKS) {
            tasks.entrySet().stream()
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue().createdAtMs()))
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

    /**
     * 纯值请求上下文（H4）：只承载提交线程捕获的标量（user / org / role / viewAll），
     * **不包装任何容器请求对象** —— 容器在请求结束后会回收并清空它，跨线程重绑必然读到
     * 「未知」；纯 Map 值没有生命周期问题，可安全跨线程读取。
     */
    static final class ValueRequestAttributes implements RequestAttributes {

        private final Map<String, Object> values = new java.util.HashMap<>();

        @Override
        public Object getAttribute(String name, int scope) {
            return name == null ? null : values.get(name);
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            if (name != null) {
                values.put(name, value);
            }
        }

        @Override
        public void removeAttribute(String name, int scope) {
            if (name != null) {
                values.remove(name);
            }
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return values.keySet().toArray(new String[0]);
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // 纯值属性不参与容器销毁回调
        }

        @Override
        public Object resolveReference(String key) {
            return values.get(key);
        }

        @Override
        public String getSessionId() {
            return null;
        }

        @Override
        public Object getSessionMutex() {
            return this;
        }
    }
}
