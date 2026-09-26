package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.common.exception.TermIndexUnavailableException;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.NlpTextComposer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.NlpBatchDTO;
import com.tcm.ehr.domain.po.NlpTask;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpTaskVO;
import com.tcm.ehr.mapper.NlpTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryFileService;
import com.tcm.ehr.service.INlpBatchService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * NLP 批量解析任务服务实现（批K·K-a）。
 *
 * <p><b>生产者-消费者</b>：{@link #queue} 存待跑任务ID，固定 {@code nlp.batch.concurrency}（默认 2）
 * 个工作线程从队列取任务执行；任务与进度写 {@code nlp_task} 表，故前端可提交后离开、轮询进度。</p>
 *
 * <ul>
 *   <li>取数走 {@link RecordFilter}（数据域 + 筛选）与分页循环，绝不一次载入全量；</li>
 *   <li>每条：{@link NlpTextComposer} 拼文本 → 抽取 → {@link EntityNormalizer} 归一 →
 *       打词典版本 → 写 {@code records.structured_data}（与单条抽取口径一致）；</li>
 *   <li>失败清单仅存前 {@value #MAX_FAILURES} 条，超出置 {@code failure_truncated}；</li>
 *   <li>取消：QUEUED 直接置 {@code CANCELLED}；RUNNING 置取消位，工作线程在条/页边界退出；</li>
 *   <li>重启（K-c）：{@code RUNNING}/{@code QUEUED} 一律标记 {@code INTERRUPTED}，可重跑。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NlpBatchServiceImpl implements INlpBatchService {

    private static final int PAGE_SIZE = 200;
    private static final int PROGRESS_EVERY = 25;
    private static final int MAX_FAILURES = 500;

    private final NlpTaskMapper taskMapper;
    private final RecordMapper recordMapper;
    private final PythonNlpClient nlpClient;
    private final EntityNormalizer entityNormalizer;
    private final IDictionaryFileService dictionaryFileService;
    private final ObjectMapper objectMapper;

    @Value("${nlp.batch.concurrency:2}")
    private int concurrency;

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    /** 运行中任务的取消位 */
    private final Set<String> cancelFlags = ConcurrentHashMap.newKeySet();
    /**
     * 按记录ID集合执行的任务（导入后自动解析）：仅内存持有。
     * 重启后这些任务被 K-c 标记为 INTERRUPTED、不会续跑，故无需落库占存储。
     */
    private final Map<String, List<String>> idBatches = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ExecutorService workers;

    // ------------------------------------------------------------------ 生命周期

    @PostConstruct
    void init() {
        // K-c：重启后未完成的任务无法续跑，标记为已中断
        int n = taskMapper.update(null, new UpdateWrapper<NlpTask>()
                .in("status", List.of(NlpTask.RUNNING, NlpTask.QUEUED))
                .set("status", NlpTask.INTERRUPTED)
                .set("current_label", null)
                .set("finished_at", LocalDateTime.now().withNano(0)));
        if (n > 0) {
            log.warn("[批解析] 重启：{} 个未完成任务已标记为『已中断』", n);
        }
        int threads = Math.max(1, concurrency);
        running = true;
        workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "nlp-batch-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            workers.submit(this::workerLoop);
        }
        log.info("[批解析] 工作线程已启动，并发 {}", threads);
    }

    @PreDestroy
    void shutdown() {
        running = false;
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private void workerLoop() {
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
            try {
                runTask(id);
            } catch (Exception e) {
                log.error("[批解析] 任务 {} 执行异常", id, e);
                markFailed(id);
            }
        }
    }

    // ------------------------------------------------------------------ 对外接口

    @Override
    public NlpTaskVO submit(NlpBatchDTO dto, String createdBy) {
        if (!nlpClient.isEnabled()) {
            throw new IllegalArgumentException("抽取服务未开启（nlp.enabled=false），无法执行批量解析");
        }
        FiltersDTO filters = dto == null ? null : dto.getFilters();
        int limit = dto == null || dto.getLimit() == null ? 0 : dto.getLimit();

        QueryWrapper<Record> wrapper = RecordFilter.build(RecordFilter.ROLE_ADMIN, filters);
        long count = recordMapper.selectCount(wrapper);
        int total = limit > 0 ? (int) Math.min(count, limit) : (int) count;

        NlpTask t = new NlpTask();
        t.setId(UUID.randomUUID().toString());
        t.setStatus(NlpTask.QUEUED);
        t.setTotal(total);
        t.setDone(0);
        t.setSuccess(0);
        t.setFailed(0);
        // 严格版：序列化失败宁可拒绝提交，也不能让任务退化成全库扫描（原用 writeJson 会静默变 "null"）
        t.setFiltersJson(writeJsonStrict(objectMapper, filters));
        t.setCreatedBy(createdBy);
        t.setFailureList("[]");
        t.setFailureTruncated(false);
        t.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(t);
        queue.offer(t.getId());
        log.info("[批解析] 已提交任务 {}：计划 {} 条", t.getId(), total);
        return toVO(t, false);
    }

    @Override
    public NlpTaskVO submitIds(List<String> ids, String createdBy) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (!nlpClient.isEnabled()) {
            throw new IllegalArgumentException("抽取服务未开启（nlp.enabled=false），已跳过自动解析");
        }
        NlpTask t = new NlpTask();
        t.setId(UUID.randomUUID().toString());
        t.setStatus(NlpTask.QUEUED);
        t.setTotal(ids.size());
        t.setDone(0);
        t.setSuccess(0);
        t.setFailed(0);
        t.setCreatedBy(createdBy);
        t.setFailureList("[]");
        t.setFailureTruncated(false);
        t.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(t);
        idBatches.put(t.getId(), new ArrayList<>(ids));
        queue.offer(t.getId());
        log.info("[批解析] 已提交按ID任务 {}：计划 {} 条", t.getId(), ids.size());
        return toVO(t, false);
    }

    @Override
    public NlpTaskVO get(String id) {
        NlpTask t = taskMapper.selectById(id);
        return t == null ? null : toVO(t, true);
    }

    @Override
    public NlpTaskVO cancel(String id) {
        NlpTask t = taskMapper.selectById(id);
        if (t == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (NlpTask.QUEUED.equals(t.getStatus())) {
            t.setStatus(NlpTask.CANCELLED);
            t.setFinishedAt(LocalDateTime.now().withNano(0));
            taskMapper.updateById(t);
        } else if (NlpTask.RUNNING.equals(t.getStatus())) {
            cancelFlags.add(id);
        }
        return toVO(t, false);
    }

    @Override
    public List<NlpTaskVO> list() {
        List<NlpTask> tasks = taskMapper.selectList(new QueryWrapper<NlpTask>()
                .orderByDesc("create_time").last("LIMIT 50"));
        List<NlpTaskVO> out = new ArrayList<>();
        for (NlpTask t : tasks) {
            out.add(toVO(t, false));
        }
        return out;
    }

    // ------------------------------------------------------------------ 执行

    private void runTask(String id) {
        NlpTask t = taskMapper.selectById(id);
        if (t == null || NlpTask.CANCELLED.equals(t.getStatus())) {
            idBatches.remove(id);
            return;
        }
        List<String> idSource = idBatches.get(id);

        t.setStatus(NlpTask.RUNNING);
        t.setStartedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);

        List<NlpTaskVO.Failure> failures = new ArrayList<>();
        boolean[] truncated = {false};
        int[] processed = {0};
        boolean cancelled = false;
        try {
            cancelled = idSource != null
                    ? runByIds(id, idSource, t, failures, truncated, processed)
                    : runByFilter(id, t, failures, truncated, processed);
        } finally {
            t.setStatus(cancelled ? NlpTask.CANCELLED : NlpTask.COMPLETED);
            t.setFinishedAt(LocalDateTime.now().withNano(0));
            t.setCurrentLabel(null);
            persistProgress(t, failures, truncated[0]);
            cancelFlags.remove(id);
            idBatches.remove(id);
            log.info("[批解析] 任务 {} 结束：{}，成功 {}，失败 {}", id, t.getStatus(), t.getSuccess(), t.getFailed());
        }
    }

    /** 按筛选范围分页处理；返回是否被取消 */
    private boolean runByFilter(String id, NlpTask t, List<NlpTaskVO.Failure> failures,
                                boolean[] truncated, int[] processed) {
        int limit = t.getTotal() == null ? 0 : t.getTotal();
        QueryWrapper<Record> wrapper = RecordFilter.build(RecordFilter.ROLE_ADMIN, readFilters(t.getFiltersJson()));
        int pageNo = 1;
        while (true) {
            if (cancelFlags.contains(id)) {
                return true;
            }
            Page<Record> page = recordMapper.selectPage(new Page<>(pageNo, PAGE_SIZE), wrapper);
            List<Record> list = page.getRecords();
            if (list.isEmpty()) {
                break;
            }
            for (Record r : list) {
                if (cancelFlags.contains(id)) {
                    return true;
                }
                if (processed[0] >= limit) {
                    return false;
                }
                step(id, r, t, failures, truncated, processed);
            }
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
        for (int off = 0; off < ids.size(); off += PAGE_SIZE) {
            if (cancelFlags.contains(id)) {
                return true;
            }
            List<String> chunk = ids.subList(off, Math.min(off + PAGE_SIZE, ids.size()));
            List<Record> list = recordMapper.selectBatchIds(chunk);
            for (Record r : list) {
                if (cancelFlags.contains(id)) {
                    return true;
                }
                step(id, r, t, failures, truncated, processed);
            }
        }
        return false;
    }

    /** 处理单条并更新进度（两条取数路径共用） */
    private void step(String id, Record r, NlpTask t, List<NlpTaskVO.Failure> failures,
                      boolean[] truncated, int[] processed) {
        processed[0]++;
        try {
            processOne(r);
            t.setSuccess(t.getSuccess() + 1);
        } catch (Exception e) {
            t.setFailed(t.getFailed() + 1);
            if (failures.size() < MAX_FAILURES) {
                failures.add(new NlpTaskVO.Failure(labelOf(r), reasonOf(e)));
            } else {
                truncated[0] = true;
            }
        }
        t.setDone(processed[0]);
        t.setCurrentLabel(labelOf(r));
        if (processed[0] % PROGRESS_EVERY == 0) {
            persistProgress(t, failures, truncated[0]);
        }
    }

    /** 单条：拼文本 → 抽取 → 归一 → 打词典版本 → 写库（与单条抽取口径一致） */
    private void processOne(Record r) throws Exception {
        String text = NlpTextComposer.compose(r);
        if (text.isBlank()) {
            throw new IllegalArgumentException("该病历无可抽取的文本字段");
        }
        NlpExtractVO vo = nlpClient.extract(text);
        if (vo == null) {
            throw new IllegalStateException("抽取服务连不上（:8001 未启动）");
        }
        entityNormalizer.normalize(vo);
        String json = objectMapper.writeValueAsString(vo);
        json = StructuredDataMeta.stamp(objectMapper, json, dictionaryFileService.currentVersion());
        recordMapper.updateStructuredData(r.getId(), json);
    }

    private void markFailed(String id) {
        NlpTask t = taskMapper.selectById(id);
        if (t == null || isTerminal(t.getStatus())) {
            return;
        }
        t.setStatus(NlpTask.FAILED);
        t.setFinishedAt(LocalDateTime.now().withNano(0));
        taskMapper.updateById(t);
    }

    private void persistProgress(NlpTask t, List<NlpTaskVO.Failure> failures, boolean truncated) {
        t.setFailureList(writeJson(failures));
        t.setFailureTruncated(truncated);
        taskMapper.updateById(t);
    }

    // ------------------------------------------------------------------ 辅助

    private static boolean isTerminal(String status) {
        return NlpTask.COMPLETED.equals(status) || NlpTask.CANCELLED.equals(status)
                || NlpTask.INTERRUPTED.equals(status) || NlpTask.FAILED.equals(status);
    }

    private static String reasonOf(Exception e) {
        if (e instanceof TermIndexUnavailableException) {
            return "术语索引不可用（ES），归一无法完成";
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? "抽取失败" : m;
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
     * <p>注意 {@code o == null} 不是失败：Jackson 把 null 序列化成字符串 {@code "null"}，
     * 读回时同样得到 {@code null}，语义是「不限范围」，这是<b>合法</b>路径
     * （提交时不带 filters 就是全库），不要连它一起禁掉。</p>
     */
    static String writeJsonStrict(ObjectMapper mapper, Object o) {
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
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "null";
        }
    }

    private FiltersDTO readFilters(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FiltersDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private NlpTaskVO toVO(NlpTask t, boolean withFailures) {
        NlpTaskVO vo = new NlpTaskVO();
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
        if (withFailures) {
            vo.setFailures(parseFailures(t.getFailureList()));
        }
        return vo;
    }

    private List<NlpTaskVO.Failure> parseFailures(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
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
