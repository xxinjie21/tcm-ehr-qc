package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.common.exception.TermIndexUnavailableException;
import com.tcm.ehr.common.utils.DictMeta;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.NlpTextComposer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.common.exception.ForbiddenException;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.NlpBatchDTO;
import com.tcm.ehr.domain.po.NlpTask;
import com.tcm.ehr.domain.po.NlpTaskItem;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpTaskVO;
import com.tcm.ehr.domain.vo.NlpTasksVO;
import com.tcm.ehr.mapper.NlpTaskMapper;
import com.tcm.ehr.mapper.NlpTaskItemMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.INlpBatchService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NLP 批量解析任务服务实现。
 *
 * <p><b>DB 轮询认领</b>（批次 16.2）：任务落库为 {@code QUEUED}，固定 {@code nlp.batch.concurrency}
 * （默认 2）个工作线程各自轮询 {@code nlp_task} 并用「{@code UPDATE ... WHERE status='QUEUED'}」
 * 的原子性认领（受影响行数为 1 才算拿到）。不引入消息中间件 —— DB 是任务状态的唯一权威，
 * 多实例下同一任务只会被一个实例跑；前端可提交后离开、轮询进度。</p>
 *
 * <ul>
 * <li>取数走 {@link RecordFilter}（数据域 + 筛选）与分页循环，绝不一次载入全量；</li>
 * <li>每条：{@link NlpTextComposer} 拼文本 → 抽取 → {@link EntityNormalizer} 归一 →
 * 打词典版本 → 写 {@code records.structured_data}（与单条抽取口径一致）；</li>
 * <li>失败清单仅存前 {@value #MAX_FAILURES} 条，超出置 {@code failure_truncated}；</li>
 * <li>取消：QUEUED 直接置 {@code CANCELLED}；RUNNING 置取消位，工作线程在条/页边界退出；</li>
 * <li>重启（K-c）：{@code RUNNING} 标记 {@code INTERRUPTED}（无法续跑，可重跑）；
 * {@code QUEUED} 是持久待认领队列，重启后由任意实例认领续跑，不再清理。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NlpBatchServiceImpl implements INlpBatchService {

    private static final int PAGE_SIZE = 200;
    private static final int PROGRESS_EVERY = 25;
    private static final int MAX_FAILURES = 500;
    /** 「最近任务」列表的上限；超出时回 truncated=true，别让页面把这一页当全部 */
    private static final int LIST_LIMIT = 50;
    /** 提交互斥锁名：跨实例只允许一个「查重 + 入库」同时进行 */
    private static final String SUBMIT_LOCK = "tcm:nlp_batch:submit";
    /** 无待认领任务时 worker 的轮询间隔（毫秒）；也就是「提交后最长多久开始跑」 */
    private static final long POLL_INTERVAL_MS = 1000;
    /** 一次认领扫描最多取几个候选：小批即可，避免一次把本实例的 worker 全占满 */
    private static final int CLAIM_BATCH = 5;

    private final NlpTaskMapper taskMapper;
    private final RecordMapper recordMapper;
    /** 批次 16 工作项 3：待处理病历明细（ID 集合落库，重启不丢） */
    private final NlpTaskItemMapper nlpTaskItemMapper;
    private final PythonNlpClient nlpClient;
    private final EntityNormalizer entityNormalizer;
    /** 词典状态（版本 / 词条数）：归一打点要用「真正生效的那版词典」，不是词典还在文件时代留下的冻结哈希 */
    private final com.tcm.ehr.service.IDictionaryTermStore termStore;
    private final ObjectMapper objectMapper;
    /** 批次 26.2：提交互斥统一走 DistLock（事务感知释放） */
    private final DistLock distLock;

    @Value("${nlp.batch.concurrency:2}")
    private int concurrency;

    /**
     * 已请求取消的任务 ID（仅本进程可见，不是跨实例的权威）。
     *
     * 跨实例权威是 nlp_task.cancel_requested 列，见 {@link #isCancelRequested(String)}；
     * 该列由 cancel() 的运行中分支写入 —— 只置本集合的话，重启或换实例取消都无效。
     */
    private final Set<String> cancelFlags = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private ExecutorService workers;

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    void init() {
        // 1. 重启兜底：上次 RUNNING 的任务无法续跑（本进程的执行栈已丢），标为已中断（可重跑）。
        //    ⚠️ 批次 16.2 起**不再清理 QUEUED**：它现在是持久化的「待认领队列」，
        //    留在库里由任意实例认领续跑；一刀切标中断会把正常排队的任务误杀。
        //    已知残留：多实例滚动重启时，B 实例启动的这一步仍会把 A 实例**正在 RUNNING**
        //    的任务标中断（对端 worker 收尾会覆写回自己的终态，属瞬态）。彻底解决需要
        //    owner/heartbeat 列，超出本工作项范围。
        //    ⚠️ 必须兜底：这一步直连 DB，而本方法由 @PostConstruct 触发，
        //    异常会向上抛成 Bean 初始化失败 → 整个应用起不来。
        //    与既有口径一致（ES / Redis 探活失败只告警不阻塞启动）：
        //    查库失败只告警，任务留在原状态，下次提交/人工处理即可。
        int n = 0;
        try {
            n = taskMapper.update(null, new UpdateWrapper<NlpTask>()
                    .eq("status", NlpTask.RUNNING)
                    .set("status", NlpTask.INTERRUPTED)
                    .set("current_label", null)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (n > 0) {
                log.warn("[批解析] 重启：{} 个未完成任务已标记为『已中断』", n);
            }
        } catch (Exception e) {
            log.error("[批解析] 重启兜底失败：未完成任务未能标记为『已中断』，（DB 可能不可用）。不影响服务启动，恢复后可在列表里手动重跑", e);
        }
        // 2. 起固定大小的守护线程池：并发固定，Python 服务不会被多个任务同时压垮
        int threads = Math.max(1, concurrency);
        running = true;
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "nlp-batch-worker");
            t.setDaemon(true);
            return t;
        });
        // 3. 每个线程跑同一个「认领任务」循环
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("[批解析] 工作线程已启动，并发 {}", threads);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (workers == null) {
            return;
        }
        workers.shutdownNow();
        // 先等 worker 收尾：它们的 finally 要写库落状态。不等就可能撞上容器销毁数据源，
        // 写失败则任务留在 RUNNING —— 只能靠下次启动 init() 兜成「已中断」。
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[批解析] 停机：worker 未在 5s 内退出，任务状态可能未落库");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 批次 16.2：停机**不再清理 QUEUED**。它们现在就是「待认领队列」，
        // 留在库里由本实例下次启动或另一个实例认领续跑；一刀切标中断会让
        // 「提交完就重启 / 滚动发布」变成任务白提交。
        // （原先必须补标，是因为任务 ID 只活在内存队列里，进程一走就没人认领了。）
    }

    private void workerLoop() {
        // 1. 常驻轮询认领（批次 16.2）：DB 是任务状态的唯一权威。
        //    多实例下靠 claimNextTask() 里「UPDATE ... WHERE status='QUEUED'」的原子性互斥，
        //    同一个任务只会被一个实例 / 线程认领；running=false 也会在轮询间隔内被看到。
        while (running) {
            String id = null;
            try {
                id = claimNextTask();
            } catch (Exception e) {
                // 认领本身失败（DB 抖动）不能让 worker 线程死掉：告警后退避重试
                log.warn("[批解析] 认领排队任务失败，{}ms 后重试: {}", POLL_INTERVAL_MS, e.getMessage());
            }
            if (id == null) {
                sleepQuietly(POLL_INTERVAL_MS);
                continue;
            }
            // 2. 执行任务；兜底异常走标记失败，避免线程静默死掉
            try {
                runTask(id);
            } catch (Exception e) {
                log.error("[批解析] 任务 {} 执行异常", id, e);
                markFailed(id);
            }
        }
    }

    /**
     * 从库里认领一个排队中的任务（批次 16.2 的核心）。
     *
     * <p>「取候选」故意不加锁：几个 worker / 几个实例查到同一批是常态。
     * 真正的互斥发生在随后的条件更新上 ——
     * {@code UPDATE nlp_task SET status='RUNNING', started_at=? WHERE id=? AND status='QUEUED'}，
     * <b>受影响行数为 1 才算认领成功</b>；为 0 说明已被别人抢先，继续看下一个候选。
     * 不写 {@code cancel_requested}：认领前后刚到达的取消必须保留，由执行期的取消位读取兜住。</p>
     *
     * @return 认领到的任务 ID；当前没有可认领的任务时为 {@code null}
     */
    String claimNextTask() {
        List<NlpTask> candidates = taskMapper.selectList(new QueryWrapper<NlpTask>()
                .eq("status", NlpTask.QUEUED)
                .orderByAsc("create_time")
                .orderByAsc("id")
                .last("LIMIT " + CLAIM_BATCH));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (NlpTask candidate : candidates) {
            int claimed = taskMapper.update(null, new UpdateWrapper<NlpTask>()
                    .eq("id", candidate.getId())
                    .eq("status", NlpTask.QUEUED)
                    .set("status", NlpTask.RUNNING)
                    .set("started_at", now));
            if (claimed == 1) {
                log.info("[批解析] 已认领排队任务 {}", candidate.getId());
                return candidate.getId();
            }
        }
        return null;
    }

    /** 睡一会儿；被打断时保留中断位尽快返回，让 workerLoop 重新检查 running */
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 按筛选范围提交批量解析任务，异步入队后立即返回。
     *
     * <p>先统计范围内病历数作为计划总数（{@code limit > 0} 时取较小值），筛选条件经严格序列化
     * 落库 —— 序列化失败直接拒绝提交，避免任务静默退化成全库扫描。任务以 QUEUED 状态入库，
     * 由工作线程轮询认领（批次 16.2）；返回的视图不含失败明细。</p>
     *
     * @param dto 批量请求（filters + 可选 limit），可为 null
     * @param createdBy 提交人
     * @return 新任务的进度视图（无失败明细）
     * @throws IllegalArgumentException 抽取服务未开启或筛选条件无法序列化时抛出
     * @throws com.tcm.ehr.common.exception.ConcurrentOperationException 拿不到提交锁（另一提交正在进行）
     */
    @Override
    @Transactional
    public NlpTaskVO submit(NlpBatchDTO dto, String createdBy) {
        // 1. 前置校验：没开抽取就不要排任务，避免整批必然失败
        if (!nlpClient.isEnabled()) {
            throw new IllegalArgumentException("抽取服务未开启（nlp.enabled=false），无法执行批量解析");
        }
        // 1.05 幂等（批次 5）：同一 request_key 的重放直接返回既有任务，不新建、不重跑。
        //      必须放在下面那道「已有任务在排队/运行」防重**之前** —— 否则重试会撞上防重而报错，
        //      而正确语义是「这就是我刚才那次提交，把它还给我」。
        String requestKey = dto == null ? null : dto.getRequestKey();
        if (requestKey != null && !requestKey.isBlank()) {
            requestKey = requestKey.trim();
            NlpTask existed = findTaskByRequestKey(RequestUtils.currentOrgId(), requestKey);
            if (existed != null) {
                log.info("[批解析] 幂等命中：key={} 复用任务 {}", requestKey, existed.getId());
                return toVO(existed, false);
            }
        }
        final String idempotencyKey = requestKey;
        final FiltersDTO filters = dto == null ? null : dto.getFilters();
        final int limit = dto == null || dto.getLimit() == null ? 0 : dto.getLimit();
        final String orgId = RequestUtils.currentOrgId();

        // 1.1 防重：「查有没有活跃任务」+「统计」+「插队」必须原子，否则并发双提交会双双入库
        //     （多个任务同时跑，进度互相覆写，还一起压 Python 服务）。
        //     ⚠️ 互斥必须跨实例：JVM 锁（synchronized/ReentrantLock）在多实例下静默失效 ——
        //     不报错、只是不互斥。批次 16.1：DistLock 用 Redisson 锁；它在事务内把放锁
        //     推迟到提交之后（否则并发的查重会读到未提交的空结果，防重形同虚设）。
        return distLock.runLocked(SUBMIT_LOCK, () -> {
            Long active = taskMapper.selectCount(new QueryWrapper<NlpTask>()
                    .in("status", List.of(NlpTask.RUNNING, NlpTask.QUEUED)));
            if (active != null && active > 0) {
                throw new IllegalArgumentException("已有解析任务在排队或运行中，请等它结束或先取消");
            }

            // 2. 统计计划条数：limit 大于 0 时以它封顶
            QueryWrapper<Record> wrapper = RecordFilter.build(orgId, filters);
            long count = recordMapper.selectCount(wrapper);
            int total = limit > 0 ? (int) Math.min(count, limit) : (int) count;

            // 3. 任务落库为排队态；筛选条件严格序列化，失败宁可拒绝也不让任务退化成全库扫描
            NlpTask t = new NlpTask();
            t.setId(UUID.randomUUID().toString());
            t.setStatus(NlpTask.QUEUED);
            t.setTotal(total);
            t.setDone(0);
            t.setSuccess(0);
            t.setFailed(0);
            t.setFiltersJson(writeJsonStrict(objectMapper, filters));
            t.setCreatedBy(createdBy);
            // 提交线程捕获组快照，供 worker 重建 RecordFilter（§ 6.3 缺点 13）
            t.setOrgId(orgId);
            t.setFailureList("[]");
            t.setFailureTruncated(false);
            t.setCreateTime(LocalDateTime.now().withNano(0));
            t.setRequestKey(idempotencyKey);
            try {
                taskMapper.insert(t);
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 并发同键：另一线程刚插入成功 ⇒ 查回那条任务返回，绝不重跑
                NlpTask existed = findTaskByRequestKey(t.getOrgId(), idempotencyKey);
                if (existed != null) {
                    log.info("[批解析] 并发同键：key={} 复用任务 {}", idempotencyKey, existed.getId());
                    return toVO(existed, false);
                }
                throw dup;
            }

            // 4. 落库后立即返回，由工作线程轮询认领、异步执行。
            //    批次 16.2：不再有内存投递，故 26.2 那个「推迟到提交后投递」的时序问题
            //    自然消失 —— 事务未提交时，别的实例与本实例的 worker 都查不到这行。
            log.info("[批解析] 已提交任务 {}：计划 {} 条", t.getId(), total);
            return toVO(t, false);
        });
    }

    /**
     * 按 (组织, 幂等键) 查任务（批次 5）。
     *
     * <p>orgId 为空时直接返回 null：早于组织标记的任务 org_id 为 NULL，不该被别的组织命中。</p>
     */
    private NlpTask findTaskByRequestKey(String orgId, String requestKey) {
        if (orgId == null || requestKey == null) {
            return null;
        }
        return taskMapper.selectOne(new QueryWrapper<NlpTask>()
                .eq("org_id", orgId)
                .eq("request_key", requestKey)
                .last("LIMIT 1"));
    }

    /**
     * 按病历 ID 集合提交批量解析任务（导入后自动解析走这里）。
     *
     * <p>任务以 QUEUED 状态入库，ID 集合仅保存在内存（重启后任务被标记为已中断，不会续跑）。
     * ID 集合为空时直接返回 null，视为无需提交。</p>
     *
     * @param ids 待解析病历 ID 列表
     * @param createdBy 提交人
     * @return 新任务的进度视图；{@code ids} 为空时为 null
     * @throws IllegalArgumentException 抽取服务未开启时抛出
     */
    @Override
    public NlpTaskVO submitIds(List<String> ids, String createdBy) {
        // 1. 空集合视为无需提交
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        // 2. 前置校验：没开抽取就跳过自动解析
        if (!nlpClient.isEnabled()) {
            throw new IllegalArgumentException("抽取服务未开启（nlp.enabled=false），已跳过自动解析");
        }
        // 3. 任务落库为排队态（不记筛选条件，范围由 ID 集合决定）
        NlpTask t = new NlpTask();
        t.setId(UUID.randomUUID().toString());
        t.setStatus(NlpTask.QUEUED);
        t.setTotal(ids.size());
        t.setDone(0);
        t.setSuccess(0);
        t.setFailed(0);
        t.setCreatedBy(createdBy);
        // 4. 组织标记必须在这里打上（B1 根因）：worker 的 runByIds 会再按 org_id 筛一遍
        //    （fail-closed），不打的话 org_id 为 NULL，而「org_id = NULL」恒不成立 →
        //    ids 变空集、跑 0 条、且因 running 仍为 true 落成 COMPLETED；列表/get/cancel
        //    又都按 org 过滤，任务在界面上完全不出现。用户视角就是
        //    「开关打开了、提示已提交，可一条都没解析、任务列表里也找不到」。
        //    本方法全仓只有一个调用点（RecordServiceImpl 的导入后自动解析），且是请求线程，
        //    所以这里取当前组织即可。
        t.setOrgId(RequestUtils.currentOrgId());
        t.setFailureList("[]");
        t.setFailureTruncated(false);
        t.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(t);

        // 5. ID 集合只留在内存（重启后任务标中断、不会续跑），再入队
        // 批次 16 #3：ID 集合落库 —— 原先只放内存，进程重启即丢，
        // 表现为「任务显示进行中、重启后再也不推进」。这里先把明细写下来；
        // 读取路径下一轮切到本表（此轮**只写不读**，行为与之前完全一致，便于分步验证）。
        // 与任务插入同一事务：否则会出现「任务在、ID 丢了」，那正是要修的形态。
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withNano(0);
        int seq = 0;
        for (String recordId : ids) {
            NlpTaskItem item = new NlpTaskItem();
            item.setTaskId(t.getId());
            item.setRecordId(recordId);
            item.setSeq(seq++);
            item.setStatus(NlpTaskItem.PENDING);
            item.setCreateTime(now);
            item.setUpdateTime(now);
            nlpTaskItemMapper.insert(item);
        }
        // 保留策略（批次 16 #3）：明细是「一行一条病历」，一次 500 条的导入就是 500 行 ——
        // 不清理会只增不减。这里在提交路径顺带清掉 30 天前的明细（不引入调度器：
        // 提交是低频写操作，够用；且按时间清理是幂等的）。任务主表 nlp_task 不动，
        // 历史任务的进度与失败清单仍在，只是明细（用于重启续跑）过期后不再保留。
        try {
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().withNano(0).minusDays(30);
            int pruned = nlpTaskItemMapper.delete(new QueryWrapper<NlpTaskItem>()
                    .lt("create_time", cutoff));
            if (pruned > 0) {
                log.info("[批解析] 清理 30 天前的任务明细 {} 行（保留策略）", pruned);
            }
        } catch (Exception e) {
            // 清理失败不能挡住提交
            log.warn("[批解析] 清理过期任务明细失败（不影响提交）: {}", e.getMessage());
        }
        // 批次 16.2：不再内存投递；任务已落库为 QUEUED，worker 轮询会认领它。
        log.info("[批解析] 已提交按ID任务 {}：计划 {} 条", t.getId(), ids.size());
        return toVO(t, false);
    }

    /**
     * 查询任务详情（含失败明细），只读。
     *
     * @param id 任务 ID
     * @return 任务进度视图（带失败明细）；任务不存在时为 null
     */
    @Override
    public NlpTaskVO get(String id) {
        NlpTask t = taskMapper.selectById(id);
        // § 6.3 缺点 10：不能看别组任务进度。
        // 不属于本组统一返回 null（按不存在处理）
        if (t != null && !satisfiesGroup(t)) {
            return null;
        }
        return t == null ? null : toVO(t, true);
    }

    /**
     * 取消任务。
     *
     * <p>排队中的任务直接置为已取消并记完成时间；运行中的任务只置内存取消位，由工作线程在
     * 条 / 页边界退出，状态待其收尾时落库。已到终态的任务不再变更。</p>
     *
     * @param id 任务 ID
     * @return 取消操作后的任务视图
     * @throws IllegalArgumentException 任务不存在时抛出
     */
    @Override
    public NlpTaskVO cancel(String id) {
        // 1. 取任务，不存在直接报错
        NlpTask t = taskMapper.selectById(id);
        if (t == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        // § 6.3 缺点 10：不能取消别组任务（写操作，该报 Forbidden）
        if (!satisfiesGroup(t)) {
            throw new ForbiddenException("无权操作该任务");
        }
        // 2. 排队中：还没被任何 worker 认领，用**带状态条件**的原子更新落终态。
        //    批次 16.2：认领同样是「UPDATE ... WHERE id=? AND status='QUEUED'」，
        //    这里若沿用无条件的 updateById，会和认领互相覆盖 —— 取消把任务改回 CANCELLED，
        //    而 worker 已认领、会继续跑并在收尾时覆写终态，取消就静默丢了。
        boolean claimedByWorker = false;
        if (NlpTask.QUEUED.equals(t.getStatus())) {
            int cancelled = taskMapper.update(null, new UpdateWrapper<NlpTask>()
                    .eq("id", id)
                    .eq("status", NlpTask.QUEUED)
                    .set("status", NlpTask.CANCELLED)
                    .set("finished_at", LocalDateTime.now().withNano(0)));
            if (cancelled == 1) {
                t.setStatus(NlpTask.CANCELLED);
                t.setFinishedAt(LocalDateTime.now().withNano(0));
                // 内存取消位一并置：本实例 worker 可能刚认领成功、还没读到状态
                cancelFlags.add(id);
            } else {
                // 受影响行数为 0：已被认领（或已进终态）。重读一次再决定走哪个分支
                t = taskMapper.selectById(id);
                claimedByWorker = t != null && NlpTask.RUNNING.equals(t.getStatus());
            }
        }
        // 3. 运行中（或刚被认领）：不能直接改状态（worker 还会覆写），取消位必须同时落内存与库。
        //    只置内存的话，worker 的 isCancelRequested 永远读到 0，
        //    跨实例与重启后的取消都会静默失效。
        if (claimedByWorker || NlpTask.RUNNING.equals(t.getStatus())) {
            cancelFlags.add(id);
            taskMapper.update(null, new UpdateWrapper<NlpTask>()
                    .eq("id", id)
                    .set("cancel_requested", 1));
            t.setCancelRequested(1);
        }
        return toVO(t, false);
    }

    /**
     * 列出最近任务（最多 {@value #LIST_LIMIT} 条，按创建时间倒序），只读。
     *
     * <p>返回项不含失败明细，需要明细请用 {@link #get(String)}。</p>
     *
     * @return 任务列表 + 截断标记（多取一条精确判定，不靠 size == 上限猜）
     */
    @Override
    public NlpTasksVO list() {
        // 1. 按创建时间倒序取本组最近 N+1 条
        //    § 6.3 缺点 9：不能看到别组的批量解析任务列表
        List<NlpTask> tasks = taskMapper.selectList(new QueryWrapper<NlpTask>()
                .eq("org_id", RequestUtils.currentOrgId())
                .orderByDesc("create_time").last("LIMIT " + (LIST_LIMIT + 1)));
        // 2. 精确判截断：恰好 N 条不算截断，N+1 条才是
        boolean truncated = tasks.size() > LIST_LIMIT;
        // 3. 不带失败明细：列表页不需要，明细走 get(id)
        List<NlpTaskVO> out = new ArrayList<>();
        for (int i = 0; i < Math.min(tasks.size(), LIST_LIMIT); i++) {
            out.add(toVO(tasks.get(i), false));
        }
        NlpTasksVO vo = new NlpTasksVO();
        vo.setTasks(out);
        vo.setTruncated(truncated);
        vo.setLimit(LIST_LIMIT);
        return vo;
    }

    // ------------------------------------------------------------------ 执行

    private void runTask(String id) {
        // 1. 取出任务；已取消或已不存在直接清理内存态了事
        NlpTask t = taskMapper.selectById(id);
        if (t == null || NlpTask.CANCELLED.equals(t.getStatus())) {
            return;
        }
        // 批次 16 #3：ID 集合改为从明细表读 —— 内存那份在进程重启后是空的，
        // 任务会永远停在「进行中」。读不到（本次部署前提交的旧任务，或历史数据）再回落到内存，
        // 这样切换本身不会让任何在跑的任务断掉。
        // ⚠️ 只有「按 ID 集合提交」的任务才有明细；**筛选型任务本来就没有明细**（也不该有）。
        //    若无条件去读明细，筛选型任务会读回空集合 → 被下面的空集合守卫直接收尾，
        //    表现为「任务 COMPLETED、0 处理 0 成功」——静默不干活（实测 15:41 那次）。
        //    判据用「该任务有没有明细行」：有 → ID 型，读 PENDING 续跑；没有 → 返回 null 走筛选路径。
        //    这与原实现的语义一致（内存里取不到 id 时为 null → runByFilter）。
        List<String> idSource = hasTaskItems(id) ? pendingIdsFor(id) : null;

        // 2. 标记运行中并记录开始时间（此刻起进度才对外可见）
        //    先清掉取消位再落库：t 是取任务时的快照，直接 updateById 会把
        //    cancel_requested=0 写回，抹掉这一步之间刚到达的取消请求
        t.setCancelRequested(null);
        t.setStatus(NlpTask.RUNNING);
        t.setStartedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);

        // 3. 按取数来源分派：导入来的按 ID 集合，其余按筛选范围
        List<NlpTaskVO.Failure> failures = new ArrayList<>();
        boolean[] truncated = {false};
        int[] processed = {0};
        boolean cancelled = false;
        boolean failed = false;
        try {
            cancelled = idSource != null
                    ? runByIds(id, idSource, t, failures, truncated, processed)
                    : runByFilter(id, t, failures, truncated, processed);
            // 批量解析改写 structured_data → 统计词频过期，主动失效（B1）
            if (!cancelled) {
                com.tcm.ehr.common.cache.StatsCacheInvalidator.invalidateStats();
            }
        } catch (Exception e) {
            // 执行期异常必须落 FAILED，且不能指望 workerLoop 的 markFailed：
            // 它的 satisfiesGroup 读的是请求上下文，worker 线程里恒为空 → 每次直接 return
            failed = true;
            log.error("[批解析] 任务 {} 执行异常，落 FAILED", id, e);
        } finally {
            // 4. 无论正常跑完、取消还是异常，都要在这里落终态，否则任务会永远停在"运行中"
            // P0-1（2026-10-05）：**不许静默报完成** —— 一条都没处理（done=0）却有个总数（total>0），
            // 说明分流或明细读取出过问题（实测过一次：500 条筛选型任务 0 处理却 COMPLETED）。
            // 这种状态下「全部成功」是假的：界面上会显示完成，而数据是空的。
            // 这里判为 FAILED（现有状态里最接近真实的一个），并打明确日志；
            // 完整的四态化（全部成功/部分失败/无待处理/已取消，含前端文案）仍留在 P0-1 后续。
            boolean nothingDone = !failed && !cancelled && t.getDone() == 0 && t.getTotal() > 0;
            if (nothingDone) {
                log.error("[批解析] 任务 {} 未处理任何记录（0/{}），判为异常终止："
                        + "请检查任务分流与明细读取，不要据此认为已完成", id, t.getTotal());
            }
            // P0-1/P0-2：终态选择抽成 terminalStatus（可同步测试的四态纯逻辑）
            t.setStatus(terminalStatus(failed, nothingDone, running, cancelled, t.getDone(), t.getTotal()));
            t.setFinishedAt(LocalDateTime.now().withNano(0));
            t.setCurrentLabel(null);
            persistProgress(t, failures, truncated[0]);
            // 批次 16 #3：任务**真正跑完**时，把该任务剩下的待处理明细批量标为 DONE。
            // 否则重启后会把已处理过的再跑一遍（至少一次语义 → 重复抽取会覆盖同一份结构化数据）。
            // 取消 / 中断 / 失败时**刻意保持 PENDING** —— 那正是「重启后从断点继续」的依据。
            if (NlpTask.COMPLETED.equals(t.getStatus())) {
                try {
                    nlpTaskItemMapper.update(null, new UpdateWrapper<NlpTaskItem>()
                            .eq("task_id", id)
                            .eq("status", NlpTaskItem.PENDING)
                            .set("status", NlpTaskItem.DONE));
                } catch (Exception e) {
                    // 标记失败不影响任务结论：最多是重启后重跑一遍（回到至少一次）
                    log.warn("[批解析] 任务 {} 标记明细完成态失败: {}", id, e.getMessage());
                }
            }
            cancelFlags.remove(id);
            log.info("[批解析] 任务 {} 结束：{}，成功 {}，失败 {}", id, t.getStatus(), t.getSuccess(), t.getFailed());
        }
    }

    /**
     * 取该任务**尚未处理**的病历 ID（按提交顺序）。
     *
     * <p>批次 16 #3 的核心：ID 集合落库后，重启也能从这里读回断点。
     * 两条性质缺一不可，且都被测试钉住：
     * ① <b>只读 {@code PENDING}</b> —— 跑完的（{@code DONE}）不能再跑一遍，否则重复抽取会覆盖
     *    同一份结构化数据；
     * ② <b>按 {@code seq} 排序</b> —— 进度游标按提交顺序推进，不能靠主键（批量插入下主键顺序
     *    与提交顺序不保证一致）。</p>
     */
    /**
     * 该任务是否**按 ID 集合提交**（＝有没有明细行）。
     *
     * <p>这是任务分流的唯一判据，也是 2026-10-05 那次静默回归的所在：筛选型任务**本来就没有明细**，
     * 若无条件去读明细会得到空集合，进而被判成「已处理完」而直接收尾（表现为 500 条任务
     * `COMPLETED · 已处理 0`）。抽出成独立方法是为了让它能被同步测试钉住 —— 线程里的流程难测，
     * 但「判据本身」好测，而会犯错的恰恰是判据。</p>
     *
     * @return true = ID 型（读 PENDING 续跑）；false = 筛选型（走 runByFilter）
     */
    boolean hasTaskItems(String taskId) {
        return nlpTaskItemMapper.selectCount(
                new QueryWrapper<NlpTaskItem>().eq("task_id", taskId)) > 0;
    }

    List<String> pendingIdsFor(String taskId) {
        return nlpTaskItemMapper.selectList(new QueryWrapper<NlpTaskItem>()
                        .eq("task_id", taskId)
                        .eq("status", NlpTaskItem.PENDING)
                        .orderByAsc("seq"))
                .stream().map(NlpTaskItem::getRecordId).toList();
    }

    /**
     * 终态选择（P0-1/P0-2，2026-10-05 抽出以便同步测试）。
     *
     * <p>四种终态互斥且都不许撒谎：</p>
     * <ul>
     *   <li>执行期抛异常 → {@code FAILED}</li>
     *   <li><b>一条都没处理（done=0 且 total&gt;0）→ {@code INTERRUPTED}</b>：这是 2026-10-05 实测到的事故
     *       （500 条筛选型任务 0 处理却 COMPLETED，界面说完成、数据是空的，用户无法自查）</li>
     *   <li>取消 → {@code CANCELLED}</li>
     *   <li>其余按完成度判定（{@link #endStatus}）</li>
     * </ul>
     * <p>抽成方法的原因：线程里的流程难测，但「终态怎么选」是可同步断言、可变异验证的纯逻辑 ——
     * 而会犯错、且犯错了用户看不出来的，恰恰是它。</p>
     */
    String terminalStatus(boolean failed, boolean nothingDone, boolean running,
                          boolean cancelled, int done, int total) {
        if (failed) {
            return NlpTask.FAILED;
        }
        if (nothingDone) {
            return NlpTask.INTERRUPTED;
        }
        return endStatus(running, cancelled, done, total);
    }

    /** 按筛选范围分页处理；返回是否被取消 */
    private boolean runByFilter(String id, NlpTask t, List<NlpTaskVO.Failure> failures,
                                boolean[] truncated, int[] processed) {
        // 1. 还原落库时的筛选条件（条件是提交时冻结的，不随数据变化）
        int limit = t.getTotal() == null ? 0 : t.getTotal();
        QueryWrapper<Record> wrapper = RecordFilter.build(t.getOrgId(), readFilters(t.getFiltersJson()));
        // 批次 25.3：词典元数据一批只取一次（惰性，见 DictMeta）
        DictMeta dictMeta = new DictMeta(termStore, t.getOrgId());
        int pageNo = 1;
        // 2. 分页循环取数：每页都先看取消位，避免停得慢
        while (true) {
            if (cancelFlags.contains(id) || isCancelRequested(id)) {
                return true;
            }
            // searchCount=false：本循环只按页取数、从不读 total；默认每页都发一次全量 COUNT，
            // 长任务下等于把同一 COUNT 重复执行几十次（审计 2026-10-09）
            Page<Record> page = recordMapper.selectPage(new Page<>(pageNo, PAGE_SIZE, false), wrapper);
            List<Record> list = page.getRecords();
            if (list.isEmpty()) {
                break;
            }
            // 3. 逐条处理：内存取消位逐条查；库里的每 PROGRESS_EVERY 条查一次 ——
            //    逐条查库在 3.5 万条量级就是 3.5 万次查询
            for (Record r : list) {
                if (cancelFlags.contains(id)
                        || (processed[0] % PROGRESS_EVERY == 0 && isCancelRequested(id))) {
                    return true;
                }
                if (processed[0] >= limit) {
                    return false;
                }
                step(id, r, t, failures, truncated, processed, dictMeta);
            }
            // 4. 不满一页即到末尾
            if (list.size() < PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        return false;
    }

    /** 按记录ID集合分块处理（导入后自动解析用）；返回是否被取消 */
    private boolean runByIds(String id, List<String> ids, NlpTask t, List<NlpTaskVO.Failure> failures,
                             boolean[] truncated, int[] processed) {
        // 0. 组织为空 = 处理范围无法确定：直接抛，由 run() 的 catch 落 FAILED。
        //    不能继续往下走 —— 下面那句是 eq("org_id", t.getOrgId())，org_id 为 NULL 时
        //    恒不成立，筛出空集，任务会「跑完 0 条还落 COMPLETED」，看起来像成功。
        //    历史（submitIds 未打组织标记时期）留下的 org_id IS NULL 任务重新执行时，
        //    也必须显式失败，否则这个静默形态会一直藏着。
        if (t.getOrgId() == null || t.getOrgId().isBlank()) {
            failures.add(new NlpTaskVO.Failure("(任务)", "任务缺少组织标记，无法确定处理范围，已中止"));
            throw new IllegalStateException("nlp_task 缺少 org_id，无法按机构筛选待处理病历");
        }
        // 1. § 6.3 缺点 11：导入时的 id 集合是本组的，但 task 里只有
        //    本组快照。为保险再筛一次：重新按 org_id 限定，
        //    避免任何路径把别组 id 混进来。
        // ⚠️ 空集合绝不能进 IN：MySQL 的 `IN ()` 是语法错误（实测报
        //    `SELECT id FROM records WHERE (org_id = ? AND id IN ()) LIMIT 0`）。
        //    走到这里说明该任务当前没有待处理项 —— 批次 16 #3 之后这一点变得**可达**：
        //    worker 从 nlp_task_items 读 PENDING，若明细全部已标 DONE（重跑/重启后再取到同一任务），
        //    读回来的就是空列表；而先前内存兜底取不到时返回 null、会走 runByFilter，于是从没暴露。
        //    这里直接返回「未被取消」：由调用方按 done/total 落终态，任务优雅收尾而不是报错。
        if (ids == null || ids.isEmpty()) {
            log.info("[批解析] 任务 {} 的明细均已完成，无需重跑（ID 型任务）", id);
            return false;
        }
        ids = recordMapper.selectList(new QueryWrapper<Record>()
                .select("id").eq("org_id", t.getOrgId()).in("id", ids)
                .last("LIMIT " + ids.size()))
                .stream().map(Record::getId).toList();
        // 批次 25.3：词典元数据一批只取一次（惰性，见 DictMeta）
        DictMeta dictMeta = new DictMeta(termStore, t.getOrgId());
        // 1. 按页大小切块：IN 过长会让 SQL 变慢
        for (int off = 0; off < ids.size(); off += PAGE_SIZE) {
            // 2. 每块开始前看取消位
            if (cancelFlags.contains(id) || isCancelRequested(id)) {
                return true;
            }
            List<String> chunk = ids.subList(off, Math.min(off + PAGE_SIZE, ids.size()));
            // 3. 一次批量取回该块，再逐条处理（库里的取消位同样每 PROGRESS_EVERY 条查一次）
            List<Record> list = recordMapper.selectBatchIds(chunk);
            for (Record r : list) {
                if (cancelFlags.contains(id)
                        || (processed[0] % PROGRESS_EVERY == 0 && isCancelRequested(id))) {
                    return true;
                }
                step(id, r, t, failures, truncated, processed, dictMeta);
            }
        }
        return false;
    }

    /** 处理单条并更新进度（两条取数路径共用） */
    private void step(String id, Record r, NlpTask t, List<NlpTaskVO.Failure> failures,
                      boolean[] truncated, int[] processed, DictMeta dictMeta) {
        // 1. 处理前先计数：done 以「已尝试」为准，失败也算一条
        processed[0]++;
        try {
            // 2. 成功则累加成功数
            processOne(r, t.getOrgId(), dictMeta);
            t.setSuccess(t.getSuccess() + 1);
        } catch (Exception e) {
            // 3. 失败累加并记明细；明细只留前 MAX_FAILURES 条，超出置截断标记
            t.setFailed(t.getFailed() + 1);
            if (failures.size() < MAX_FAILURES) {
                failures.add(new NlpTaskVO.Failure(labelOf(r), reasonOf(e)));
            } else {
                truncated[0] = true;
            }
        }
        // 4. 回写进度与当前条目标签（页面据此显示"正在处理第几条"）
        t.setDone(processed[0]);
        t.setCurrentLabel(labelOf(r));
        // 5. 每 PROGRESS_EVERY 条落一次库：每条都写会把库压垮
        if (processed[0] % PROGRESS_EVERY == 0) {
            persistProgress(t, failures, truncated[0]);
        }
    }

    /**
     * 读 DB 判「是否已请求取消」。
     *
     * <p>内存 {@code cancelFlags} 只有本进程可见，多实例下会失效；这里以列为准。</p>
     * <p>查库失败按<b>未取消</b>：宁可多跑几条，也不因一次抖动中断长任务。</p>
     */
    private boolean isCancelRequested(String id) {
        try {
            NlpTask t = taskMapper.selectById(id);
            return t != null && t.getCancelRequested() != null && t.getCancelRequested() == 1;
        } catch (Exception e) {
            log.debug("[批解析] 读取消位失败，按未取消处理: {}", e.getMessage());
            return false;
        }
    }

    /** 单条：拼文本 → 抽取 → 归一 → 打词典版本 → 写库（与单条抽取口径一致） */
    private void processOne(Record r, String orgId, DictMeta dictMeta) throws Exception {
        // 1. 拼可抽取文本；空文本直接判失败，不去调抽取服务
        String text = NlpTextComposer.compose(r);
        if (text.isBlank()) {
            throw new IllegalArgumentException("该病历无可抽取的文本字段");
        }
        // 2. 调抽取服务；返回 null 说明服务没起来
        NlpExtractVO vo = nlpClient.extract(text);
        if (vo == null) {
            throw new IllegalStateException("抽取服务连不上（:8001 未启动）");
        }
        // 3. 归一到标准术语（ES 索引不可用时抛异常，由上层计失败）
        entityNormalizer.normalize(vo, orgId);
        // 3.1 §九 ④：抽取器把长词切短（"天麻"→"天"）或整段漏掉（脉位）时，
        //     用处方 / 中医诊断两列原文回补。异常触发，无未归一项就完全不触发。
        entityNormalizer.backfillFromRaw(vo, orgId, r.getPrescription(), r.getTcmDiagnosis(), r.getPattern());
        // 4. 打上词典版本再写库：归一结果与当时词典版本必须成对，否则事后无法判断该不该重算
        String json = objectMapper.writeValueAsString(vo);
        // ⚠️ 原先打的是 dictionaryFileService.currentVersion() —— 那是词典还在文件时代
        //    的文件哈希。词典源在批次 8b 已入库，文件哈希从此冻结不变，库里 500 条记录
        //    的 dictVersion 全是同一个值，既无法区分、也不是真正用的那版词典。
        //    这里改用「本组织归一实际覆盖的 5 类词典」的有效版本 + 词条数。
        // 批次 25.3：元数据走批级 DictMeta（一批只查一次库），不再逐条查
        json = StructuredDataMeta.stamp(objectMapper, json,
                dictMeta.version(),
                dictMeta.termCount());
        recordMapper.updateStructuredData(r.getId(), json);
    }

    /**
     * 批次 25.3：一次批解析里词典元数据只取一次（{@link DictMeta}）。
     *
     * <p>实现已上提为共享组件 —— 清洗链路（{@code GovernanceServiceImpl.clean()}）存在同一问题，
     * 两处必须共用同一份记忆化逻辑，否则「一处修了、另一处又漏」会重演。</p>
     */
    private void markFailed(String id) {
        // 1. 不存在或已是终态就不用改（终态不能被回退）
        NlpTask t = taskMapper.selectById(id);
        // 2. 不属于本组的任务不设法打失败（保护别组任务的真实进度）
        if (t != null && !satisfiesGroup(t)) {
            return;
        }
        if (t == null || isTerminal(t.getStatus())) {
            return;
        }
        // 2. 落失败终态
        t.setStatus(NlpTask.FAILED);
        t.setFinishedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);
    }

    private void persistProgress(NlpTask t, List<NlpTaskVO.Failure> failures, boolean truncated) {
        // 1. 失败明细与截断标记一起落库，进度和明细始终同一条记录
        t.setFailureList(writeJson(failures));
        t.setFailureTruncated(truncated);
        // 2. 不把库里的取消位写回：t 是任务开始时的快照，updateById 会带上
        //    cancel_requested=0，抹掉并发取消。置 null 后 NOT_NULL 策略会跳过该列
        t.setCancelRequested(null);
        taskMapper.updateById(t);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 任务收尾时的最终状态。
     *
     * <p>停机（{@code running == false}）且<b>没跑完</b> → {@code INTERRUPTED}：不能声称「已完成」。
     * 这里必须自己判 running —— {@link #runByFilter} / {@link #runByIds} 只认 {@code cancelFlags}、
     * 不看 running，在途任务会在停机窗口里继续跑到取数循环自然结束。</p>
     *
     * <p>反过来也要留神：停机窗口内<b>恰好跑完</b>的（{@code done == total}）仍是
     * {@code COMPLETED}，别把真跑完的误标成中断。取消优先于中断。</p>
     *
     * <p>单独抽出来是为了能直接测这三条分支：靠「停一次服务」验证成本很高 ——
     * Windows 上 SIGTERM 是硬杀（{@code TerminateProcess}），根本不跑 {@code @PreDestroy}。</p>
     */
    static String endStatus(boolean running, boolean cancelled, Integer done, Integer total) {
        // 1. 停机且没跑完 → 中断（不能声称"已完成"）
        boolean unfinished = done != null && total != null && done < total;
        if (!running && !cancelled && unfinished) {
            return NlpTask.INTERRUPTED;
        }
        // 2. 取消优先于完成
        return cancelled ? NlpTask.CANCELLED : NlpTask.COMPLETED;
    }

    private static boolean isTerminal(String status) {
        // 四个终态：完成 / 取消 / 中断 / 失败
        return NlpTask.COMPLETED.equals(status) || NlpTask.CANCELLED.equals(status)
                || NlpTask.INTERRUPTED.equals(status) || NlpTask.FAILED.equals(status);
    }

    private static String reasonOf(Exception e) {
        // 1. 索引不可用单独给一句人话，否则前端只会显示裸异常名
        if (e instanceof TermIndexUnavailableException) {
            return "术语索引不可用（ES），归一无法完成";
        }
        // 2. 其余取异常消息，空的兜底成「抽取失败」
        String m = e.getMessage();
        return m == null || m.isBlank() ? "抽取失败" : m;
    }

    private static String labelOf(Record r) {
        // 1. 优先用登记号做标签，没有就用 ID
        String label = r.getRegistrationNo();
        if (label == null || label.isBlank()) {
            label = r.getId();
        }
        // 2. 截到 200 字：这列会写进 current_label，不能被超长内容撑爆
        return label == null ? "" : (label.length() > 200 ? label.substring(0, 200) : label);
    }

    /**
     * 提交期的严格序列化：失败即抛，任务不提交。
     *
     * <p>筛选条件序列化失败若被吞掉，{@link #readFilters} 会把结果读成 {@code null}，
     * 任务就从「指定范围」静默退化成「全库扫描」—— 用户以为只跑了筛出来的几百条。</p>
     *
     * <p>注意 {@code o == null} 不是失败：Jackson 把 null 序列化成字符串 {@code "null"}，
     * 读回时同样得到 {@code null}，语义是「不限范围」，这是<b>合法</b>路径
     * （提交时不带 filters 就是全库），不要连它一起禁掉。</p>
     */
    static String writeJsonStrict(ObjectMapper mapper, Object o) {
        // 序列化失败即抛：筛选条件丢了会让任务从"指定范围"静默变成"全库扫描"
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException("筛选条件无法序列化，任务未提交：" + e.getMessage());
        }
    }

    /**
     * 进度落库用的宽松序列化：失败退回 {@code "null"}（读回即「无失败明细」）。
     *
     * <p>刻意与提交路径分开：这条在任务收尾里被调用，抛异常会盖掉任务本身的收尾；
     * 而失败明细丢了不影响任务语义。</p>
     */
    private String writeJson(Object o) {
        // 1. 序列化失败退回 "null"（读回即无明细），不抛
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
     * 解析失败若也返回 null，<b>指定范围的任务会静默变成全库解析</b>。
     * 提交侧已用 writeJsonStrict 保证写入合法，此处是「历史脏数据」的兜底。</p>
     */
    private FiltersDTO readFilters(String json) {
        // 1. null / 空 / 字面 "null" 都表示「提交时未限定范围」
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        // 2. 解析不了：让任务失败，不默默扩大成全库
        try {
            return objectMapper.readValue(json, FiltersDTO.class);
        } catch (Exception e) {
            log.error("[批解析] 筛选条件解析失败，任务将终止（不回退成全库范围）：{}", json, e);
            throw new BusinessException(4003, "任务筛选条件损坏，无法执行。请重新提交一次");
        }
    }

    /** § 6.3 缺点 10：任务是否属于当前组（不含管理员特权：管理员也走组过滤，与病历访问同一口径） */
    private boolean satisfiesGroup(NlpTask t) {
        if (t == null) {
            return false;
        }
        String g1 = t.getOrgId();
        String g2 = RequestUtils.currentOrgId();
        return g1 != null && g2 != null && !g2.isBlank() && g1.equals(g2);
    }

    /** 任务实体 → 视图；withFailures 为 false 时不带失败清单（列表接口用，省流量） */
    private NlpTaskVO toVO(NlpTask t, boolean withFailures) {
        NlpTaskVO vo = new NlpTaskVO();
        // 1. 逐字段搬运，计数为 null 时归零（前端不必判空）
        vo.setId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setTotal(t.getTotal() == null ? 0 : t.getTotal());
        vo.setDone(t.getDone() == null ? 0 : t.getDone());
        vo.setSuccess(t.getSuccess() == null ? 0 : t.getSuccess());
        vo.setFailed(t.getFailed() == null ? 0 : t.getFailed());
        vo.setCurrent(t.getCurrentLabel());
        vo.setCreatedBy(t.getCreatedBy());
        vo.setCreateTime(t.getCreateTime());
        vo.setStartedAt(t.getStartedAt());
        vo.setFinishedAt(t.getFinishedAt());
        vo.setFailureTruncated(Boolean.TRUE.equals(t.getFailureTruncated()));
        // 2. 失败明细按需带上（列表接口不带，省流量）
        if (withFailures) {
            vo.setFailures(parseFailures(t.getFailureList()));
        }
        return vo;
    }

    /** 解析失败清单 JSON；坏了就当空清单，不影响进度展示 */
    private List<NlpTaskVO.Failure> parseFailures(String json) {
        // 1. 没有明细就给空清单
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        // 2. 解析失败同样给空清单：明细坏了不该让整个任务页报错
        try {
            List<NlpTaskVO.Failure> list = objectMapper.readValue(json,
                    new TypeReference<List<NlpTaskVO.Failure>>() {
                    });
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
