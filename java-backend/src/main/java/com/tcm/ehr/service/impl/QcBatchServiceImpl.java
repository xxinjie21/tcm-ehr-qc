package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.exception.BusinessException;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.common.config.QcRuleSet;
import com.tcm.ehr.common.config.QcRuleStore;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.OperationLogger;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.QcBatchDTO;
import com.tcm.ehr.domain.po.QcTask;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.common.exception.ResourceNotFoundException;
import com.tcm.ehr.domain.vo.QcBatchResultVO;
import com.tcm.ehr.domain.vo.QcTaskVO;
import com.tcm.ehr.mapper.QcTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IQcBatchService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 批量质控重算任务服务（§七 L5）。
 *
 * <p><b>为什么要异步</b>：原实现是同步的 —— 一个请求线程从头跑到尾，40000 条会把前端请求
 * 挂到超时（前端超时 200s），还长时间占着一条 DB 连接。改成生产/消费后，提交即返回
 * taskId，页面轮询进度；单条处理逻辑复用 {@link QcServiceImpl#processOne}。</p>
 *
 * <p><b>生产/消费</b>：{@link #queue} 存待跑任务 ID，固定 {@code qc.batch.concurrency}
 * （默认 1）个工作线程消费；任务与进度写 {@code qc_task} 表，故提交后可关页面、回来轮询。</p>
 *
 * <ul>
 *   <li><b>角色快照（最关键）</b>：{@code RequestUtils.currentRole()} 读的是线程绑定的
 *   {@code RequestContextHolder}，worker 线程里取到的是字符串 {@code "unknown"}（不是 null）。
 *   若拿它去 {@code RecordFilter.build}，{@code domainGrade} 返回 null，数据域过滤会退化成
 *   「不过滤 = 全库」—— 表面跑通了，实际重算了用户看不见的病历。故提交线程把角色写进
 *   {@code qc_task.role}，worker 用捕获值重建过滤器。
 *   同理结尾的审计日志用 {@link OperationLogger#log(String, String, String, String, String)}
 *   显式回填操作人，否则会记成 {@code "unknown"}。</li>
 *   <li><b>防重不用 Redis 锁</b>：改为「表中是否已有 QUEUED/RUNNING」。原来的
 *   {@code tcm:task:batch} 是全局单键，任意管理员的提交会让其他人排队，TTL 900s 固定
 *   还会在长任务中途过期导致并发双跑；查表更准、也没有 TTL 问题。</li>
 *   <li>失败清单仅存前 {@value #MAX_FAILURES} 条，超出置 {@code failure_truncated}；</li>
 *   <li>取消：QUEUED 直接置 {@code CANCELLED}；RUNNING 置取消位，worker 在页边界退出；</li>
 *   <li>重启：{@code RUNNING}/{@code QUEUED} 一律标 {@code INTERRUPTED}，可重跑。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcBatchServiceImpl implements IQcBatchService {

    private static final int PAGE_SIZE = 1000;
    /** 每处理多少条落一次进度：每条都写会把库压垮 */
    private static final int PROGRESS_EVERY = 25;
    private static final int MAX_FAILURES = 500;
    /** 任务列表最多返回条数 */
    private static final int LIST_LIMIT = 50;

    /** 提交互斥锁名：跨实例只允许一个「查重 + 入队」同时进行 */
    private static final String SUBMIT_LOCK = "tcm:qc_batch:submit";

    private final QcTaskMapper taskMapper;
    private final RecordMapper recordMapper;
    private final QcServiceImpl qcService;
    private final QcRuleStore ruleStore;
    private final OperationLogger operationLogger;
    private final ObjectMapper objectMapper;
    /** 批次 26.2：提交互斥统一走 DistLock（事务感知释放） */
    private final DistLock distLock;

    @Value("${qc.batch.concurrency:1}")
    private int concurrency;

    /** 提交时的单次范围上限（§七 L4 抬到 40000）；配合异步化后它退化为「提交时校验」 */
    @Value("${qc.batch.max-records:40000}")
    private int maxRecords;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    /** 运行中任务的取消位 */
    /**
     * 已请求取消的任务 ID。
     *
     * <p>存在内存里只为<b>当前进程内</b>判得快，但<b>不是权威</b>：多实例下另一个实例
     * 看不到它。权威是 {@code qc_task.cancel_requested} 列（批次 4 加的），
     * 见 {@link #isCancelRequested(String)}。</p>
     */
    private final Set<String> cancelFlags = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ExecutorService workers;

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    void init() {
        // 1. 重启兜底：上次没跑完的任务无法续跑，统一标为已中断（可重跑）
        //    ⚠️ 必须兜底：这一步直连 DB，而本方法由 @PostConstruct 触发，
        //    异常会向上抛成 Bean 初始化失败 → 整个应用起不来。
        //    与既有口径一致（ES / Redis 探活失败只告警不阻塞启动）：
        //    查库失败只告警，任务留在原状态，下次提交/人工处理即可。
        int n = 0;
        try {
            n = taskMapper.update(null, new UpdateWrapper<QcTask>()
                    .in("status", List.of(QcTask.RUNNING, QcTask.QUEUED))
                    .set("status", QcTask.INTERRUPTED)
                    .set("current_label", null)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (n > 0) {
                log.warn("[批重算] 重启：{} 个未完成任务已标记为『已中断』", n);
            }
        } catch (Exception e) {
            log.error("[批重算] 重启兜底失败：未完成任务未能标记为『已中断』，（DB 可能不可用）。不影响服务启动，恢复后可在列表里手动重跑", e);
        }
        // 2. 起固定大小的守护线程池（重算写密集，并发固定为 1）
        int threads = Math.max(1, concurrency);
        running = true;
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "qc-batch-worker");
            t.setDaemon(true);
            return t;
        });
        // 3. 每个线程跑同一个「取任务」循环
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("[批重算] 工作线程已启动，并发 {}", threads);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (workers == null) {
            return;
        }
        workers.shutdownNow();
        // 先等 worker 收尾：它们的 finally 要写库落状态。不等就可能撞上容器销毁数据源，
        // 写失败则任务留在 RUNNING —— 只能靠下次启动 init() 兜成「已中断」
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[批重算] 停机：worker 未在 5s 内退出，任务状态可能未落库");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 还排在队列里、没被 worker 取走的任务没有 finally 可跑，在这里补标
        int queued = taskMapper.update(null, new UpdateWrapper<QcTask>()
                .in("status", List.of(QcTask.QUEUED))
                .set("status", QcTask.INTERRUPTED)
                .set("current_label", null)
                .set("finished_at", LocalDateTime.now().withNano(0)));
        if (queued > 0) {
            log.warn("[批重算] 停机：{} 个排队任务已标记为『已中断』", queued);
        }
    }

    private void workerLoop() {
        // 1. 常驻轮询取任务：1s 间隔，running=false 能被及时看到
        while (running) {
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
            // 2. 执行任务；兜底异常走标记失败，避免线程静默死亡
            try {
                runTask(id);
            } catch (Exception e) {
                log.error("[批重算] 任务 {} 执行异常", id, e);
                markFailed(id);
            }
        }
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 按筛选范围提交批量重算任务，异步入队后立即返回。
     *
     * <p>筛选条件与<b>角色</b>都在提交线程冻结进 {@code qc_task}：worker 是后台线程，
     * 读不到 {@code RequestContextHolder}（见类注释「角色快照」）。</p>
     *
     * @param dto 批量请求（filters），可为 null（表示不限）
     * @return 新任务的进度视图（无失败明细）
     * @throws IllegalArgumentException 超出单次上限、或已有任务在排队/运行中时抛出
     * @throws com.tcm.ehr.common.exception.ConcurrentOperationException 拿不到提交锁（另一提交正在进行）
     */
    @Override
    @Transactional
    public QcTaskVO submit(QcBatchDTO dto) {
        FiltersDTO filters = dto == null ? null : dto.getFilters();
        // 1. 在提交线程取操作人与角色（worker 拿不到，必须现在捕获）
        String operator = RequestUtils.currentUsername();
        String role = RequestUtils.currentRole();
        // 数据域快照：阶段 2 后 RecordFilter 取的是 orgId 而不是 role
        String orgId = RequestUtils.currentOrgId();

        // 1.5 幂等（批次 5）：同一 request_key 的重放直接返回既有任务。
        //     放在 GET_LOCK **之前** —— 重放不是并发冲突，不该去抢锁，更不该被「已有任务在跑」拒绝。
        String requestKey = dto == null ? null : dto.getRequestKey();
        if (requestKey != null && !requestKey.isBlank()) {
            requestKey = requestKey.trim();
            QcTask existed = findTaskByRequestKey(orgId, requestKey);
            if (existed != null) {
                log.info("[批重算] 幂等命中：key={} 复用任务 {}", requestKey, existed.getId());
                return toVO(existed, false);
            }
        }

        // 2. 防重：「查有没有活跃任务」+「插队」必须原子，否则并发双提交会双双入库
        //    （两个任务同时跑，进度互相覆写）。
        //    ⚠️ 互斥用 DB 命名锁而不是 JVM 锁：synchronized 在多实例下静默失效 ——
        //    不报错、只是不互斥，是最难查的一类 bug。拿不到锁就当并发冲突拒绝，
        //    绝不「没锁也继续」—— 那正是原来 check-then-act 的老问题。
        //    批次 26.2：统一走 DistLock —— 它在事务内把放锁推迟到提交之后，且与取锁共用
        //    同一条事务连接（命名锁是连接级的，自己 GET_LOCK/RELEASE_LOCK 会漏锁）。
        return distLock.runLocked(SUBMIT_LOCK, () -> {
            // 防重按组织算：任务是组织级的，A 组排队不该挡住 B 组提交
            Long active = taskMapper.selectCount(new QueryWrapper<QcTask>()
                    .eq("org_id", orgId)
                    .in("status", List.of(QcTask.QUEUED, QcTask.RUNNING)));
            if (active != null && active > 0) {
                throw new IllegalArgumentException("已有重算任务在排队或运行中，请等它结束或先取消");
            }
            return insertTask(dto, filters, orgId, operator, role);
        });
    }

    /**
     * 按 (组织, 幂等键) 查任务（批次 5）。
     *
     * <p>orgId 为空直接返回 null：org_id 为 NULL 的历史任务不该被任何组织按键命中。</p>
     */
    private QcTask findTaskByRequestKey(String orgId, String requestKey) {
        if (orgId == null || requestKey == null) {
            return null;
        }
        return taskMapper.selectOne(new QueryWrapper<QcTask>()
                .eq("org_id", orgId)
                .eq("request_key", requestKey)
                .last("LIMIT 1"));
    }

    /** 查重通过后真正落库的那一段（被提交互斥包住） */
    private QcTaskVO insertTask(QcBatchDTO dto, FiltersDTO filters, String orgId,
                               String operator, String role) {
        // 3. 用提交线程的角色构造数据域过滤，统计计划条数。
        //    必须用 buildWithinOrg：worker 在后台线程按 org_id 快照重建条件，那里
        //    viewAllOrgs() 恒为 false；管理员提交时若用 build() 计数会算成全库，
        //    total 与实际执行范围对不上（分级汇总的分母也跟着错）
        long count = recordMapper.selectCount(RecordFilter.buildWithinOrg(orgId, filters));
        if (count > maxRecords) {
            throw new IllegalArgumentException("本次范围 " + count + " 条，超过单次上限 " + maxRecords
                    + " 条。请按科室或就诊时间分批重算。");
        }
        int total = (int) count;

        // 4. 任务落库为排队态；筛选条件严格序列化，失败宁可拒绝也不让任务退化成全库扫描
        QcTask t = new QcTask();
        t.setId(UUID.randomUUID().toString());
        t.setStatus(QcTask.QUEUED);
        t.setTotal(total);
        t.setDone(0);
        t.setSuccess(0);
        t.setFailed(0);
        t.setQualified(0);
        t.setPendingReview(0);
        t.setInvalid(0);
        t.setFiltersJson(writeJsonStrict(filters));
        t.setCreatedBy(operator);
        t.setRole(role);
        t.setOrgId(orgId);
        t.setFailureList("[]");
        t.setFailureTruncated(false);
        t.setCreateTime(LocalDateTime.now().withNano(0));
        // 批次5：幂等键落库（空白视同未提供，与前置检查同一口径）
        String rk = dto == null ? null : dto.getRequestKey();
        t.setRequestKey(rk == null || rk.isBlank() ? null : rk.trim());
        try {
            taskMapper.insert(t);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发同键：另一提交刚插入成功 ⇒ 查回那条任务返回，绝不重跑
            QcTask existed = findTaskByRequestKey(orgId, t.getRequestKey());
            if (existed != null) {
                log.info("[批重算] 并发同键：key={} 复用任务 {}", t.getRequestKey(), existed.getId());
                return toVO(existed, false);
            }
            throw dup;
        }

        // 5. 入队后立即返回，由工作线程异步消费。
        //    批次 26.2：入队推迟到事务提交之后（行可见才投递），否则 worker 可能先于提交取到 id
        DistLock.afterCommit(() -> queue.offer(t.getId()));
        operationLogger.log("批量重算", RecordFilter.describe(filters), "提交任务，计划 " + total + " 条", operator, role);
        log.info("[批重算] 已提交任务 {}：计划 {} 条", t.getId(), total);
        return toVO(t, false);
    }

    @Override
    public QcTaskVO get(String id) {
        QcTask t = taskMapper.selectById(id);
        // 不属于本组织一律按「不存在」返回：读操作不区分「不存在」与「无权查看」
        return t == null || !belongsToCurrentOrg(t) ? null : toVO(t, true);
    }

    /**
     * 任务是否属于当前组织。
     *
     * 与 NlpBatchServiceImpl.satisfiesGroup 同口径：不含管理员特权，管理员也按组织过滤 ——
     * 批任务带失败明细（含 recordId），沿用「与病历访问同一口径」比另开一套规则更好维护。
     * 无组织用户 currentOrgId() 为空串，一律判否，与病历读取的 fail-closed 一致。
     */
    private boolean belongsToCurrentOrg(QcTask t) {
        if (t == null) {
            return false;
        }
        String taskOrg = t.getOrgId();
        String currentOrg = RequestUtils.currentOrgId();
        return taskOrg != null && currentOrg != null && !currentOrg.isBlank()
                && taskOrg.equals(currentOrg);
    }

    @Override
    public QcTaskVO cancel(String id) {
        QcTask t = taskMapper.selectById(id);
        if (t == null) {
            // 抛 ResourceNotFoundException 而非 IllegalArgumentException：
            // 「不存在」是 404，「参数非法」才是 400，两者语义不同
            throw new ResourceNotFoundException(1008, "任务不存在");
        }
        // 写操作按「不许」报 403：不能把「存在但看不到」说成「不存在」
        if (!belongsToCurrentOrg(t)) {
            throw new ForbiddenException("无权操作该任务");
        }
        if (QcTask.QUEUED.equals(t.getStatus())) {
            // 1. 排队中：还没进 worker，直接落终态
            t.setStatus(QcTask.CANCELLED);
            t.setFinishedAt(LocalDateTime.now().withNano(0));
            taskMapper.updateById(t);
            // 内存取消位一并置：worker 可能已 poll 到该任务、还没读状态，
            // 只改库会让它拿旧对象覆写成 RUNNING（check-then-act 竞态）
            cancelFlags.add(id);
        } else if (QcTask.RUNNING.equals(t.getStatus())) {
            // 2. 运行中：不能直接改状态（worker 还会覆写），只置取消位让它自己收尾
            cancelFlags.add(id);
            // 取消位落库：内存只有本进程看得见，worker 也可能不在同一实例
            taskMapper.update(null, new UpdateWrapper<QcTask>()
                    .eq("id", id)
                    .set("cancel_requested", 1));
        }
        return toVO(t, false);
    }

    @Override
    public List<QcTaskVO> list() {
        // 只列本组织的任务：任务行带失败明细（含 recordId），跨组织可见等于泄露他组病历标识
        List<QcTask> tasks = taskMapper.selectList(new QueryWrapper<QcTask>()
                .eq("org_id", RequestUtils.currentOrgId())
                .orderByDesc("create_time").last("LIMIT " + LIST_LIMIT));
        List<QcTaskVO> out = new ArrayList<>();
        for (QcTask t : tasks) {
            out.add(toVO(t, false));
        }
        return out;
    }

    // ------------------------------------------------------------------ 执行

    private void runTask(String id) {
        // 1. 取出任务；已取消或已不存在直接返回
        QcTask t = taskMapper.selectById(id);
        if (t == null || QcTask.CANCELLED.equals(t.getStatus())) {
            return;
        }

        // 2. 标记运行中并记录开始时间（此刻起进度才对外可见）
        //    先清掉取消位再落库：t 是取任务时的快照，直接 updateById 会把
        //    cancel_requested=0 写回，抹掉这一步之间刚到达的取消请求
        t.setCancelRequested(null);
        t.setStatus(QcTask.RUNNING);
        t.setStartedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);

        // 3. 复用 QcBatchResultVO 累计分级统计（与单条 processOne 的入参同一套）
        QcBatchResultVO result = new QcBatchResultVO();
        result.setTotal(t.getTotal() == null ? 0 : t.getTotal());
        Set<String> seenHash = new HashSet<>();
        // 规则快照整批共用（避免逐条重复读取）。
        // 必须按任务行上的组织取：单条评分走的是 getFor(当前组织)，
        // 整批若用 get()（进程内基线）就会与单条结论分叉
        QcRuleSet rules = ruleStore.getFor(t.getOrgId());
        List<QcTaskVO.Failure> failures = new ArrayList<>();
        boolean[] truncated = {false};
        int[] processed = {0};
        boolean cancelled = false;
        boolean failed = false;
        try {
            cancelled = run(id, t, result, seenHash, rules, failures, truncated, processed);
        } catch (Exception e) {
            // 执行期异常必须落 FAILED。
            // 不能指望 workerLoop 的 markFailed：finally 会先把终态写成 COMPLETED，
            // 而 markFailed 见终态即 return，FAILED 就永远落不下来。
            failed = true;
            log.error("[批重算] 任务 {} 执行异常，落 FAILED", id, e);
        } finally {
            // 4. 无论正常跑完、取消还是异常，都要在这里落终态，否则任务会永远停在运行中
            t.setStatus(failed ? QcTask.FAILED
                    : endStatus(running, cancelled, processed[0], t.getTotal()));
            t.setFinishedAt(LocalDateTime.now().withNano(0));
            t.setCurrentLabel(null);
            t.setDone(processed[0]);
            // success 以「已尝试 - 失败」为准：原式用 total - failed，取消 / 中断提前退出时
            // 会把没处理的记录也算成成功，出现 done=25 而 success=1000 的自相矛盾
            t.setSuccess(Math.max(0, processed[0] - result.getFailed()));
            t.setFailed(result.getFailed());
            t.setQualified(result.getQualified());
            t.setPendingReview(result.getPendingReview());
            t.setInvalid(result.getInvalid());
            t.setFailureList(writeJson(failures));
            t.setFailureTruncated(truncated[0]);
            // 不把库里的取消位写回：t 是任务开始时的快照，updateById 会带上 cancel_requested=0，
            // 抹掉并发取消。置 null 后 MyBatis-Plus 的 NOT_NULL 策略会跳过该列。
            t.setCancelRequested(null);
            taskMapper.updateById(t);
            cancelFlags.remove(id);
            // 5. 审计日志用提交时捕获的操作人/角色回填 —— worker 线程读不到请求上下文
            operationLogger.log("批量重算", RecordFilter.describe(readFilters(t.getFiltersJson())),
                    "完成 " + processed[0] + " 条，合格 " + result.getQualified()
                            + "，待复核 " + result.getPendingReview()
                            + "，无效 " + result.getInvalid()
                            + "，失败 " + result.getFailed(),
                    t.getCreatedBy(), t.getRole(), t.getOrgId());
            log.info("[批重算] 任务 {} 结束：{}，成功 {}，失败 {}",
                    id, t.getStatus(), t.getSuccess(), t.getFailed());
        }
    }

    /**
     * 按筛选范围分页处理；返回是否被取消。
     *
     * <p>⚠️ 用 {@code t.getOrgId()}（提交时快照）而不是 {@code RequestUtils.currentOrgId()}：
     * worker 是后台线程，后者会拿到空串 → fail-closed → 一条也处理不了（静默失败）。</p>
     */
    private boolean run(String id, QcTask t, QcBatchResultVO result, Set<String> seenHash, QcRuleSet rules,
                        List<QcTaskVO.Failure> failures, boolean[] truncated, int[] processed) {
        // 1. 还原落库时的筛选条件（提交时冻结，不随数据变化）
        QueryWrapper<Record> wrapper = RecordFilter.build(t.getOrgId(), readFilters(t.getFiltersJson()));
        int pageNo = 1;
        // 2. 分页循环取数：每页都先看取消位，避免停不下来
        while (true) {
            if (cancelFlags.contains(id) || isCancelRequested(id)) {
                return true;
            }
            Page<Record> page = recordMapper.selectPage(new Page<>(pageNo, PAGE_SIZE), wrapper);
            List<Record> list = page.getRecords();
            if (list.isEmpty()) {
                break;
            }
            // 3. 逐条处理：内存取消位逐条查（同实例取消立即生效）；库里的取消位每
            //    PROGRESS_EVERY 条查一次 —— 逐条查库在 3.5 万条量级就是 3.5 万次查询
            for (Record r : list) {
                if (cancelFlags.contains(id)
                        || (processed[0] % PROGRESS_EVERY == 0 && isCancelRequested(id))) {
                    return true;
                }
                step(id, r, t, result, seenHash, rules, failures, truncated, processed);
            }
            // 4. 不满一页即到末尾
            if (list.size() < PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        return false;
    }

    /** 处理单条并更新进度 */
    /**
     * 读 DB 判「是否已请求取消」。
     *
     * <p>为什么必须查库：内存 {@code cancelFlags} 只有本进程可见，多实例下
     * 「A 实例点取消、B 实例在跑」会完全失效。</p>
     *
     * <p>查库失败按<b>未取消</b>处理：宁可多跑几条，也不要因为一次查询抖动就把长任务中断掉。</p>
     */
    private boolean isCancelRequested(String id) {
        try {
            QcTask t = taskMapper.selectById(id);
            return t != null && t.getCancelRequested() != null && t.getCancelRequested() == 1;
        } catch (Exception e) {
            log.debug("[批重算] 读取消位失败，按未取消处理: {}", e.getMessage());
            return false;
        }
    }

    private void step(String id, Record r, QcTask t, QcBatchResultVO result, Set<String> seenHash,
                      QcRuleSet rules, List<QcTaskVO.Failure> failures, boolean[] truncated, int[] processed) {
        // 1. 处理前先计数：done 以「已尝试」为准，失败也算一条
        processed[0]++;
        try {
            // 2. 单条处理复用 QcServiceImpl（含评分 + 回写 + upsertReviewTask + 分级计数）
            qcService.processOne(r, result, seenHash, rules);
        } catch (Exception e) {
            // 3. 失败累加并记明细；明细只留前 MAX_FAILURES 条，超出置截断标记
            result.setFailed(result.getFailed() + 1);
            if (failures.size() < MAX_FAILURES) {
                failures.add(new QcTaskVO.Failure(r.getId(), reasonOf(e)));
            } else {
                truncated[0] = true;
            }
            log.warn("[批重算] 病历 {} 失败: {}", r.getId(), e.getMessage());
        }
        // 4. 回写进度与当前条目标签（页面据此显示「正在处理第几条」）
        t.setDone(processed[0]);
        t.setCurrentLabel(labelOf(r));
        t.setQualified(result.getQualified());
        t.setPendingReview(result.getPendingReview());
        t.setInvalid(result.getInvalid());
        t.setFailed(result.getFailed());
        // 5. 每 PROGRESS_EVERY 条落一次库：每条都写会把库压垮
        //    只 set 进度列，不用整实体 updateById —— 后者会把 t 里过期的
        //    cancel_requested=0 一并写回，抹掉并发下刚置的取消位
        if (processed[0] % PROGRESS_EVERY == 0) {
            taskMapper.update(null, new UpdateWrapper<QcTask>()
                    .eq("id", id)
                    .set("done", processed[0])
                    .set("current_label", t.getCurrentLabel())
                    .set("qualified", result.getQualified())
                    .set("pending_review", result.getPendingReview())
                    .set("invalid", result.getInvalid())
                    .set("failed", result.getFailed()));
        }
    }

    private void markFailed(String id) {
        QcTask t = taskMapper.selectById(id);
        if (t == null || isTerminal(t.getStatus())) {
            return;
        }
        t.setStatus(QcTask.FAILED);
        t.setFinishedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 任务收尾时的最终状态。
     *
     * <p>停机（{@code running == false}）且<b>没跑完</b> → {@code INTERRUPTED}：不能声称「已完成」。
     * 这里必须自己判 {@code running} —— 执行循环只认 {@code cancelFlags}，不看 running，
     * 在途任务会在停机窗口里继续跑到取数循环自然结束。</p>
     *
     * <p>反过来也要留神：停机窗口里<b>恰好跑完</b>的（{@code done == total}）仍是
     * {@code COMPLETED}，别把真跑完的误标成中断。取消优先于中断。</p>
     */
    static String endStatus(boolean running, boolean cancelled, Integer done, Integer total) {
        boolean unfinished = done != null && total != null && done < total;
        if (!running && !cancelled && unfinished) {
            return QcTask.INTERRUPTED;
        }
        return cancelled ? QcTask.CANCELLED : QcTask.COMPLETED;
    }

    private static boolean isTerminal(String status) {
        return QcTask.COMPLETED.equals(status) || QcTask.CANCELLED.equals(status)
                || QcTask.INTERRUPTED.equals(status) || QcTask.FAILED.equals(status);
    }

    private static String reasonOf(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? "重算失败" : m;
    }

    private static String labelOf(Record r) {
        String label = r.getRegistrationNo();
        if (label == null || label.isBlank()) {
            label = r.getId();
        }
        return label == null ? "" : (label.length() > 200 ? label.substring(0, 200) : label);
    }

    /**
     * 提交期的严格序列化：失败即抛，任务不提交。
     *
     * <p>筛选条件序列化失败若被吞掉，{@link #readFilters} 会把结果读成 {@code null}，
     * 任务就从「指定范围」静默退化成「全库扫描」—— 用户以为只跑了筛出来的几百条。</p>
     *
     * <p>注意 {@code o == null} 不是失败：Jackson 把 null 序列化成字符 {@code "null"}，
     * 读回时同样得到 {@code null}，语义是「不限范围」，这是<b>合法</b>路径
     * （提交时不带 filters 就是全库），不要连它一起禁掉。</p>
     */
    String writeJsonStrict(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("筛选条件无法序列化，任务未提交：" + e.getMessage());
        }
    }

    /** 进度落库用的宽松序列化：失败退成 {@code "null"}（读回即「无失败明细」） */
    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "null";
        }
    }

    /**
     * 还原任务落库时冻结的筛选条件。
     *
     * <p>⚠️ <b>解析失败必须抛，不能退化成 null</b>：{@code null} 在下游表示「不限范围」，
     * 解析失败若也返回 null，<b>指定范围的任务会静默变成全库重算</b>——
     * 这是本组后果最重的一处（会改写全库评分）。提交侧已用 writeJsonStrict 保证写入合法，
     * 这里抛异常是「历史脏数据」的兜底：宁可任务失败可查，也不能默默扩大范围。</p>
     *
     * @param json 任务行上的 filters_json
     * @return 筛选条件；{@code null}/空/字面 {@code "null"} 表示「提交时未限定范围」
     */
    private FiltersDTO readFilters(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FiltersDTO.class);
        } catch (Exception e) {
            log.error("[批重算] 筛选条件解析失败，任务将终止（不回退成全库范围）：{}", json, e);
            throw new BusinessException(4003, "任务筛选条件损坏，无法执行。请重新提交一次");
        }
    }

    /** 任务实体 → 视图；withFailures 为 false 时不带失败清单（列表接口用，省流量） */
    private QcTaskVO toVO(QcTask t, boolean withFailures) {
        QcTaskVO vo = new QcTaskVO();
        vo.setId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setTotal(orZero(t.getTotal()));
        vo.setDone(orZero(t.getDone()));
        vo.setSuccess(orZero(t.getSuccess()));
        vo.setFailed(orZero(t.getFailed()));
        vo.setQualified(orZero(t.getQualified()));
        vo.setPendingReview(orZero(t.getPendingReview()));
        vo.setInvalid(orZero(t.getInvalid()));
        vo.setCurrent(t.getCurrentLabel());
        vo.setCreatedBy(t.getCreatedBy());
        vo.setCreateTime(t.getCreateTime());
        vo.setStartedAt(t.getStartedAt());
        vo.setFinishedAt(t.getFinishedAt());
        vo.setFailureTruncated(Boolean.TRUE.equals(t.getFailureTruncated()));
        if (withFailures) {
            vo.setFailures(parseFailures(t.getFailureList()));
        }
        return vo;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    /** 解析失败清单 JSON；坏了就当空清单，不影响进度展示 */
    private List<QcTaskVO.Failure> parseFailures(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<QcTaskVO.Failure> list = objectMapper.readValue(json,
                    new TypeReference<List<QcTaskVO.Failure>>() {
                    });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
