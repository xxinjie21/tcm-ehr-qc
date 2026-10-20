package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tcm.ehr.common.exception.ConcurrentOperationException;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.CleanDTO;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.po.CleanTask;
import com.tcm.ehr.domain.vo.CleanResultVO;
import com.tcm.ehr.domain.vo.CleanTaskVO;
import com.tcm.ehr.mapper.CleanTaskMapper;
import com.tcm.ehr.service.ICleanBatchService;
import com.tcm.ehr.service.IGovernanceService;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 数据清洗的异步任务编排：提交 / 进度 / 取消 / 后台执行。
 *
 * <p><b>为什么改异步</b>：原 {@code POST /api/governance/clean} 是同步接口 —— 一个请求线程
 * 从头跑到尾（全库 3.4 万条约数分钟），前端只能挂到超时，且<b>无法停止</b>。改为后台任务后
 * 提交即返回任务 ID，可轮询进度、可取消、关页面也不影响执行。</p>
 *
 * <p><b>与 QcBatchServiceImpl 同构</b>：条件认领、取消位落库、终态兜底、重启标记中断这套
 * 口径完全照搬 —— 两处若各写一套，改状态机时必然漏一处。差异只在「跑什么」：
 * 质控是逐条评分，清洗是调一次 {@link IGovernanceService#clean} 并传进度回调。</p>
 */
@Service
public class CleanBatchService implements ICleanBatchService {

    private static final Logger log = LoggerFactory.getLogger(CleanBatchService.class);

    /** 提交互斥锁名（与质控/解析分开，互不影响） */
    private static final String SUBMIT_LOCK = "lock:clean:submit";

    private final CleanTaskMapper taskMapper;
    private final IGovernanceService governanceService;
    private final DistLock distLock;
    private final ObjectMapper objectMapper;

    @Value("${clean.batch.concurrency:1}")
    private int concurrency;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    /** 运行中任务的取消位（内存快判；权威是 clean_task.cancel_requested 列） */
    private final Set<String> cancelFlags = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ExecutorService workers;

    @Autowired
    private ThreadPoolTaskExecutor cleanBatchExecutor;

    public CleanBatchService(CleanTaskMapper taskMapper, IGovernanceService governanceService,
                             DistLock distLock, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.governanceService = governanceService;
        this.distLock = distLock;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ 生命周期

    @PostConstruct
    void init() {
        // 重启兜底：上次没跑完的任务无法续跑，统一标为已中断（可重跑）
        try {
            int n = taskMapper.update(null, new UpdateWrapper<CleanTask>()
                    .in("status", List.of(CleanTask.RUNNING, CleanTask.QUEUED))
                    .set("status", CleanTask.INTERRUPTED)
                    .set("current_label", null)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (n > 0) {
                log.warn("[清洗] 重启：{} 个未完成任务已标记为『已中断』", n);
            }
        } catch (Exception e) {
            log.error("[清洗] 重启兜底失败（DB 可能不可用）。不影响服务启动，恢复后可手动重跑", e);
        }
        int threads = Math.max(1, concurrency);
        running = true;
        workers = cleanBatchExecutor.getThreadPoolExecutor();
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("[清洗] 工作线程已启动，并发 {}", threads);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (workers == null) {
            return;
        }
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[清洗] 停机：worker 未在 5s 内退出，任务状态可能未落库");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            int queued = taskMapper.update(null, new UpdateWrapper<CleanTask>()
                    .in("status", List.of(CleanTask.QUEUED))
                    .set("status", CleanTask.INTERRUPTED)
                    .set("current_label", null)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (queued > 0) {
                log.warn("[清洗] 停机：{} 个排队任务已标记为『已中断』", queued);
            }
        } catch (Exception e) {
            log.warn("[清洗] 停机补标排队任务失败（下次启动会兜成『已中断』）: {}", e.getMessage());
        }
    }

    private void workerLoop() {
        log.info("[清洗] worker 线程启动: {}", Thread.currentThread().getName());
        while (running) {
            log.debug("[清洗] worker 轮询中, 队列长度={}", queue.size());
            String id;
            try {
                id = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (id == null) {
                continue;
            }
            try {
                runTask(id);
            } catch (Exception e) {
                log.error("[清洗] 任务 {} 执行异常", id, e);
                markFailed(id);
            }
        }
    }

    // ------------------------------------------------------------ 对外接口

    /**
     * 提交清洗任务（异步入队，立即返回）。
     *
     * <p>范围过滤与组织快照在提交线程冻结进 {@code clean_task}：worker 是后台线程，
     * 读不到 {@code RequestContextHolder}。</p>
     */
    @Transactional(rollbackFor = Exception.class)
        @Override
    public CleanTaskVO submit(CleanDTO dto) {
        FiltersDTO filters = dto == null ? null : dto.getFilters();
        boolean skipNormalize = dto != null && Boolean.TRUE.equals(dto.getSkipNormalize());
        String operator = RequestUtils.currentUsername();
        String orgId = RequestUtils.currentOrgId();

        return distLock.runLocked(SUBMIT_LOCK, () -> {
            // 防重按组织算：A 组排队不该挡住 B 组提交
            Long active = taskMapper.selectCount(new QueryWrapper<CleanTask>()
                    .eq("org_id", orgId)
                    .in("status", List.of(CleanTask.QUEUED, CleanTask.RUNNING)));
            if (active != null && active > 0) {
                throw new IllegalArgumentException("已有清洗任务在排队或运行中，请等它结束或先取消");
            }
            return insertTask(dto, filters, orgId, operator, skipNormalize);
        });
    }

    private CleanTaskVO insertTask(CleanDTO dto, FiltersDTO filters, String orgId,
                                   String operator, boolean skipNormalize) {
        CleanTask t = new CleanTask();
        t.setId(UUID.randomUUID().toString());
        t.setStatus(CleanTask.QUEUED);
        t.setTotal(0);
        t.setDone(0);
        t.setSuccess(0);
        t.setFailed(0);
        t.setDeduped(0);
        t.setRepaired(0);
        t.setIsolated(0);
        t.setNormalized(0);
        t.setSkipNormalize(skipNormalize ? 1 : 0);
        t.setCancelRequested(0);
        t.setFailureTruncated(false);
        t.setCreatedBy(operator);
        t.setOrgId(orgId);
        t.setFiltersJson(writeJson(filters));
        t.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(t);
        // ⚠️ 必须在<b>事务提交后</b>才入队（与 QcBatchServiceImpl 同口径，用 DistLock.afterCommit）。
        // 在事务内 queue.offer 会让 worker 立刻 poll 到 id，但它去 selectById 时该行尚未提交
        // —— 读到 null，runTask 静默 return，任务永远停在 QUEUED（本 bug 的实际表现）。
        DistLock.afterCommit(() -> queue.offer(t.getId()));
        log.info("[清洗] 已提交任务 {}（组织={}，跳过归一={}）", t.getId(), orgId, skipNormalize);
        return toVO(t, true);
    }

    /** 查任务（轮询进度用） */
        @Override
    public CleanTaskVO status(String id) {
        CleanTask t = taskMapper.selectById(id);
        if (t == null) {
            throw new IllegalArgumentException("清洗任务不存在: " + id);
        }
        return toVO(t, true);
    }

    /** 最近任务列表（按创建时间倒序） */
        @Override
    public List<CleanTaskVO> list() {
        List<CleanTask> ts = taskMapper.selectList(new QueryWrapper<CleanTask>()
                .orderByDesc("create_time").last("LIMIT 20"));
        return ts.stream().map(t -> toVO(t, false)).toList();
    }

    /**
     * 请求取消：只发取消信号，不自己把状态改成 CANCELLED。
     *
     * <p>口径与质控重算一致：终态由执行方（worker 的 finally）落 —— 提交方直接改成
     * CANCELLED 会与「任务其实已经跑完」竞争，把 COMPLETED 抹成 CANCELLED。</p>
     */
        @Override
    public CleanTaskVO cancel(String id) {
        CleanTask t = taskMapper.selectById(id);
        if (t == null) {
            throw new IllegalArgumentException("清洗任务不存在: " + id);
        }
        // 1. 已进终态：无需取消
        if (!CleanTask.QUEUED.equals(t.getStatus()) && !CleanTask.RUNNING.equals(t.getStatus())) {
            return toVO(t, true);
        }
        // 2. 取消位落库（权威，跨实例可见）+ 内存快判
        taskMapper.update(null, new UpdateWrapper<CleanTask>()
                .eq("id", id)
                .set("cancel_requested", 1));
        cancelFlags.add(id);
        // 3. 还在排队（未被 worker 取走）：没有执行方会落终态，这里直接落 CANCELLED
        if (CleanTask.QUEUED.equals(t.getStatus())) {
            int n = taskMapper.update(null, new UpdateWrapper<CleanTask>()
                    .eq("id", id)
                    .eq("status", CleanTask.QUEUED)
                    .set("status", CleanTask.CANCELLED)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (n == 1) {
                cancelFlags.remove(id);
            }
        }
        return toVO(taskMapper.selectById(id), true);
    }

    // ------------------------------------------------------------ 执行

    private void runTask(String id) {
        CleanTask t = taskMapper.selectById(id);
        if (t == null || CleanTask.CANCELLED.equals(t.getStatus())) {
            cancelFlags.remove(id);
            return;
        }
        // 条件认领：只有还停在 QUEUED 才置 RUNNING（让路于并发到达的取消）
        LocalDateTime startedAt = LocalDateTime.now().withNano(0);
        int claimed = taskMapper.update(null, new UpdateWrapper<CleanTask>()
                .eq("id", id)
                .eq("status", CleanTask.QUEUED)
                .set("status", CleanTask.RUNNING)
                .set("started_at", startedAt));
        if (claimed != 1) {
            cancelFlags.remove(id);
            return;
        }
        t.setStatus(CleanTask.RUNNING);
        t.setStartedAt(startedAt);

        boolean cancelled = false;
        boolean failed = false;
        CleanResultVO result = null;
        try {
            FiltersDTO filters = readFilters(t.getFiltersJson());
            boolean skipNorm = t.getSkipNormalize() != null && t.getSkipNormalize() == 1;
            final String tid = id;
            // ⚠️ worker 是后台线程，读不到 RequestContextHolder —— 而 clean() 内部多处调
            // RequestUtils.currentOrgId()（RecordFilter.build / 预热 / 词典元数据）。
            // 不绑定上下文时 currentOrgId() 取到 null，RecordFilter 退化成「查不到任何数据」，
            // 任务会「秒完成但处理 0 条」——本 bug 的实际表现。
            // 故用提交时冻结的 orgId 快照临时绑定一个请求上下文，执行完在 finally 清理；
            // 同时按用户要求把 role 也设为管理员口径（viewAllOrgs），与提交者视角一致。
            bindWorkerContext(t.getOrgId());
            // 进度回调：每 200 条写一次进度；返回 false 表示已请求取消
            java.util.function.IntPredicate progress = done -> {
                CleanTask upd = new CleanTask();
                upd.setId(tid);
                upd.setDone(done);
                upd.setCurrentLabel(String.valueOf(done));
                taskMapper.updateById(upd);
                return !isCancelRequested(tid);
            };
            result = governanceService.clean(null, filters, skipNorm, progress);
            cancelled = isCancelRequested(id);
        } catch (Exception e) {
            failed = true;
            log.error("[清洗] 任务 {} 执行异常，落 FAILED", id, e);
        } finally {
            CleanTask upd = new CleanTask();
            upd.setId(id);
            upd.setStatus(failed ? CleanTask.FAILED : (cancelled ? CleanTask.CANCELLED : CleanTask.COMPLETED));
            upd.setFinishedAt(LocalDateTime.now().withNano(0));
            upd.setCurrentLabel(null);
            if (result != null) {
                // clean() 中止时 total 已被改写为「实际处理数」（见 GovernanceServiceImpl），
                // 正常跑完时它就是查询总数 —— 两种情况都对。
                int done = result.getTotal();
                upd.setDone(done);
                upd.setSuccess(done);
                upd.setTotal(done);
                upd.setDeduped(result.getDeduped());
                upd.setRepaired(result.getRepaired());
                upd.setIsolated(result.getIsolated());
                upd.setNormalized(result.getNormalized());
            }
            taskMapper.updateById(upd);
            cancelFlags.remove(id);
            clearWorkerContext();
        }
    }

    /**
     * 为后台 worker 绑定请求上下文（orgId 用任务快照；role 按管理员口径）。
     *
     * <p>用自实现的 {@code SimpleRequestAttributes} 而不是 Spring 的 Servlet 实现：
     * 后者要真的 {@code HttpServletRequest}，而 mock 包只在测试 classpath 上。</p>
     */
    private void bindWorkerContext(String orgId) {
        SimpleRequestAttributes attrs = new SimpleRequestAttributes();
        attrs.setAttribute(RequestUtils.ATTR_ORG_ID, orgId, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_ROLE, RequestUtils.ROLE_ADMIN, RequestAttributes.SCOPE_REQUEST);
        attrs.setAttribute(RequestUtils.ATTR_VIEW_ALL_ORGS, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        RequestContextHolder.setRequestAttributes(attrs);
    }

    /** 最小 RequestAttributes 实现：只支持 SCOPE_REQUEST 的存取（worker 只需这三个属性） */
    private static final class SimpleRequestAttributes implements RequestAttributes {
        private final java.util.Map<String, Object> attrs = new java.util.HashMap<>();

        @Override
        public Object getAttribute(String name, int scope) {
            return attrs.get(name);
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            attrs.put(name, value);
        }

        @Override
        public void removeAttribute(String name, int scope) {
            attrs.remove(name);
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return attrs.keySet().toArray(new String[0]);
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
            // worker 不需要销毁回调
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return null;
        }

        @Override
        public Object getSessionMutex() {
            return null;
        }
    }

    /** 清理 worker 的请求上下文（线程会被复用，不清理会串到下个任务） */
    private void clearWorkerContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 取消位判定：内存快判优先，未命中再读库（多实例下另一实例置的位只能靠库） */
    private boolean isCancelRequested(String id) {
        if (cancelFlags.contains(id)) {
            return true;
        }
        CleanTask t = taskMapper.selectById(id);
        return t != null && t.getCancelRequested() != null && t.getCancelRequested() == 1;
    }

    private void markFailed(String id) {
        CleanTask t = taskMapper.selectById(id);
        if (t == null || CleanTask.COMPLETED.equals(t.getStatus())
                || CleanTask.CANCELLED.equals(t.getStatus()) || CleanTask.FAILED.equals(t.getStatus())) {
            return;
        }
        CleanTask upd = new CleanTask();
        upd.setId(id);
        upd.setStatus(CleanTask.FAILED);
        upd.setFinishedAt(LocalDateTime.now().withNano(0));
        upd.setCurrentLabel(null);
        taskMapper.updateById(upd);
    }

    // ------------------------------------------------------------ 工具

    private CleanTaskVO toVO(CleanTask t, boolean withResult) {
        CleanTaskVO vo = new CleanTaskVO();
        vo.setId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setTotal(t.getTotal());
        vo.setDone(t.getDone());
        vo.setSuccess(t.getSuccess());
        vo.setFailed(t.getFailed());
        vo.setSkipNormalize(t.getSkipNormalize() != null && t.getSkipNormalize() == 1);
        vo.setDeduped(t.getDeduped());
        vo.setRepaired(t.getRepaired());
        vo.setIsolated(t.getIsolated());
        vo.setNormalized(t.getNormalized());
        vo.setCreateTime(t.getCreateTime());
        vo.setFinishedAt(t.getFinishedAt());
        return vo;
    }

    private String writeJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("[清洗] 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private FiltersDTO readFilters(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FiltersDTO.class);
        } catch (Exception e) {
            log.warn("[清洗] 反序列化 filters 失败: {}", e.getMessage());
            return null;
        }
    }
}
