package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tcm.ehr.common.config.AsyncConfig;
import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.common.utils.EntityNormalizer;
import com.tcm.ehr.common.utils.PythonNlpClient;
import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.common.utils.StructuredDataMeta;
import com.tcm.ehr.domain.po.NlpTask;
import com.tcm.ehr.domain.po.NlpTaskItem;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.domain.vo.NlpTaskVO;
import com.tcm.ehr.mapper.NlpTaskItemMapper;
import com.tcm.ehr.mapper.NlpTaskMapper;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NLP 批量解析链路的集成测试网（批次 25.12）。
 *
 * <p>补的是原先缺的那一类断言：全仓无 {@code @SpringBootTest}，而 Mockito 单测把 Mapper 全部
 * stub 掉，没有一条同时钉住「终态 + 已处理数 + 成功/失败数」。这里用真库跑整条 worker 链路，
 * 断言任务终态与三个计数互相自洽。</p>
 *
 * <p><b>为什么是 H2 而不是真 MySQL</b>：真库要先建库/清库，机器上没有 MySQL 时测试网会静默失效，
 * 反而给出「全绿」的假象；整应用上下文又需要 Redis / ES / Docker 在线，会把验收闸门绑死在环境上。
 * 这里只装配被测链路需要的最小上下文：H2（MODE=MySQL）+ 三个 Mapper + 一个真实例。</p>
 *
 * <p><b>外部依赖全部换成 mock</b>：抽取服务（PythonNlpClient）、归一（EntityNormalizer）、
 * 词典版本（IDictionaryTermStore）都不是本链路的被测对象，网络与 ES 依赖会引入不确定性。</p>
 */
@SpringJUnitConfig(NlpBatchIntegrationTest.TestConfig.class)
class NlpBatchIntegrationTest {

    /** 内存库；DB_CLOSE_DELAY=-1 让库在连接关闭后仍存活于 JVM 内 */
    private static final String JDBC_URL =
            "jdbc:h2:mem:nlp_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    private static final String ORG = "org-it-1";
    private static final String OTHER_ORG = "org-it-2";

    private static final Set<String> TERMINAL = Set.of(
            NlpTask.COMPLETED, NlpTask.CANCELLED, NlpTask.INTERRUPTED, NlpTask.FAILED);

    @Autowired
    private NlpBatchServiceImpl service;

    /**
     * 用 spy 包住真 Mapper：既能照常读写 H2，又能数「取了几页」。
     *
     * <p>这是 A2 回归唯一可观测的信号 —— 触顶后多翻页**不会**改变 done/total/success
     * （页被裁成空、一条也不处理），只会白白多发查询。</p>
     */
    @MockitoSpyBean
    private RecordMapper recordMapper;

    @Autowired
    private NlpTaskMapper taskMapper;

    @Autowired
    private NlpTaskItemMapper itemMapper;

    @MockitoBean
    private PythonNlpClient nlpClient;

    @MockitoBean
    private EntityNormalizer entityNormalizer;

    @MockitoBean
    private IDictionaryTermStore termStore;

    @BeforeEach
    void setUp() {
        itemMapper.delete(new QueryWrapper<>());
        taskMapper.delete(new QueryWrapper<>());
        recordMapper.delete(new QueryWrapper<>());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestUtils.ATTR_ORG_ID, ORG);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        when(nlpClient.isEnabled()).thenReturn(true);
        when(nlpClient.extract(anyString())).thenReturn(NlpExtractVO.empty());
        when(termStore.effectiveDictVersion(anyString())).thenReturn("dict-v1");
        when(termStore.effectiveTermCount(anyString())).thenReturn(42);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void idTaskRunsToCompletionWithSelfConsistentCounts() {
        List<String> ids = List.of(
                insertRecord(ORG, "主诉：一"),
                insertRecord(ORG, "主诉：二"),
                insertRecord(ORG, "主诉：三"));

        NlpTaskVO submitted = service.submitIds(ids, "tester");
        assertNotNull(submitted);
        assertEquals(NlpTask.QUEUED, submitted.getStatus());
        assertEquals(3, submitted.getTotal());

        NlpTask task = awaitTerminal(submitted.getId());

        assertEquals(NlpTask.COMPLETED, task.getStatus());
        assertEquals(3, task.getTotal());
        assertEquals(3, task.getDone());
        assertEquals(3, task.getSuccess());
        assertEquals(0, task.getFailed());

        List<NlpTaskItem> items = itemMapper.selectList(
                new QueryWrapper<NlpTaskItem>().eq("task_id", submitted.getId()));
        assertEquals(3, items.size());
        assertTrue(items.stream().allMatch(i -> NlpTaskItem.DONE.equals(i.getStatus())),
                "任务完成后明细应全部为 DONE，否则重启会重跑已处理过的病历");

        for (String id : ids) {
            String json = recordMapper.selectById(id).getStructuredData();
            assertNotNull(json, "结构化数据未写库：" + id);
            assertTrue(json.contains(StructuredDataMeta.META_KEY), "缺少 _meta 词典版本戳：" + json);
            assertTrue(json.contains("dict-v1"), "词典版本戳不是本组织生效的那一版：" + json);
        }
    }

    @Test
    void singleFailureIsCountedReportedAndDoesNotHideSuccesses() {
        String badId = insertRecord(ORG, "主诉：抽取坏");
        List<String> ids = new ArrayList<>();
        ids.add(insertRecord(ORG, "主诉：一"));
        ids.add(badId);
        ids.add(insertRecord(ORG, "主诉：二"));
        // 抽取服务对这条返回 null，等价于「服务连不上」
        when(nlpClient.extract(contains("抽取坏"))).thenReturn(null);

        NlpTaskVO submitted = service.submitIds(ids, "tester");
        NlpTask task = awaitTerminal(submitted.getId());

        assertEquals(NlpTask.COMPLETED, task.getStatus(), "有成功条目的任务不应整体失败");
        assertEquals(3, task.getTotal());
        assertEquals(3, task.getDone());
        assertEquals(2, task.getSuccess());
        assertEquals(1, task.getFailed());

        NlpTaskVO detail = service.get(submitted.getId());
        assertEquals(1, detail.getFailures().size(), "失败清单必须逐条可查");
        assertTrue(detail.getFailures().get(0).getReason().contains("抽取服务连不上"),
                "失败原因应是人话：" + detail.getFailures().get(0).getReason());
        assertNull(recordMapper.selectById(badId).getStructuredData(), "失败条不应写入结构化数据");
    }

    @Test
    void idsOutsideCurrentOrgAreFilteredOutAndTaskIsNotReportedAsCompleted() {
        List<String> foreign = List.of(
                insertRecord(OTHER_ORG, "外组一"),
                insertRecord(OTHER_ORG, "外组二"),
                insertRecord(OTHER_ORG, "外组三"));

        NlpTaskVO submitted = service.submitIds(foreign, "tester");
        NlpTask task = awaitTerminal(submitted.getId());

        // 组织隔离 fail-closed：别组 ID 全被筛掉 → 一条都没处理，绝不能伪装成完成
        assertEquals(NlpTask.INTERRUPTED, task.getStatus());
        assertEquals(3, task.getTotal());
        assertEquals(0, task.getDone());
        assertEquals(0, task.getSuccess());
        assertEquals(0, task.getFailed());
    }

    /**
     * A2 回归守卫：筛选型任务在触到 {@code total} 上限后必须停止翻页。
     *
     * <p><b>被测语义</b>：{@code runByFilter} 传给 {@code processPage} 的 {@code limit}
     * 是<b>任务的 total</b>（{@code runByFilter:738}），不是 DTO 的 limit。
     * 处理数达到 total 之后，剩下的页一条都不该再取。</p>
     *
     * <p><b>为什么这条断言必须数查询次数</b>：触顶后多翻的页会被裁成空切片，一条也不处理 ——
     * done / total / success 与「正确停止」完全一样。旧实现 {@code processPage} 返回
     * {@code false}，而外层把 {@code false} 理解成「未取消、继续翻页」，于是把匹配集翻到底：
     * 450 条 / PAGE_SIZE=200 时会取 3 页而不是 2 页。40k 条 + limit=1000 时是 200 页 vs 6 页。</p>
     */
    @Test
    void filterTaskStopsPagingOnceTotalIsReached() {
        // 450 条 > PAGE_SIZE(200)：触顶后仍剩整页可取，新旧实现的差别才暴露出来
        for (int i = 0; i < 450; i++) {
            insertRecord(ORG, "主诉：上限" + i);
        }
        // 直接落一条 QUEUED 的筛选型任务（total=10），由 worker 认领后走 runByFilter。
        // 刻意不走 service.submit()：它内部的 selectCount 会带上 RecordFilter 的 ORDER BY，
        // 而 H2 不允许「COUNT(*) + ORDER BY」（MySQL 允许）—— 本测试网的环境限制，非产品缺陷。
        NlpTask queued = new NlpTask();
        queued.setId(UUID.randomUUID().toString());
        queued.setStatus(NlpTask.QUEUED);
        queued.setTotal(10);
        queued.setDone(0);
        queued.setSuccess(0);
        queued.setFailed(0);
        queued.setOrgId(ORG);
        queued.setFiltersJson("null");
        queued.setFailureList("[]");
        queued.setFailureTruncated(false);
        queued.setCreatedBy("tester");
        queued.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(queued);

        NlpTask task = awaitTerminal(queued.getId());

        assertEquals(NlpTask.COMPLETED, task.getStatus());
        assertEquals(10, task.getTotal());
        assertEquals(10, task.getDone());
        assertEquals(10, task.getSuccess(), "处理条数不得因停止翻页而改变");
        assertEquals(0, task.getFailed());

        // 第 1 页取 200 条、处理 10 条后返回 CONTINUE；第 2 页一进来就发现已触顶 → 恰好 2 次
        verify(recordMapper, times(2)).selectList(ArgumentMatchers.<Wrapper<Record>>any());
    }

    /**
     * 需求4：「仅归一」任务不调 NLP 抽取，只对已有结构化数据重跑归一。
     *
     * <p>直接落一条 QUEUED 的 {@code mode=NORMALIZE_ONLY} 筛选型任务（同 {@link #filterTaskStopsPagingOnceTotalIsReached}
     * 规避 H2 的 COUNT+ORDER BY 限制），验证：抽取服务零调用、归一被调用、词典版本戳被重打。</p>
     */
    @Test
    void normalizeOnlyTaskSkipsNlpAndRenormalizesStoredData() {
        String id = insertRecordWithStructured(ORG,
                "{\"patternList\":[{\"content\":\"肝郁气滞\",\"sourceText\":\"肝郁气滞\"}],"
                        + "\"_meta\":{\"dictVersion\":\"old\"}}");

        NlpTask queued = new NlpTask();
        queued.setId(UUID.randomUUID().toString());
        queued.setStatus(NlpTask.QUEUED);
        queued.setMode(NlpTask.MODE_NORMALIZE_ONLY);
        queued.setTotal(1);
        queued.setDone(0);
        queued.setSuccess(0);
        queued.setFailed(0);
        queued.setOrgId(ORG);
        queued.setFiltersJson("null");
        queued.setFailureList("[]");
        queued.setFailureTruncated(false);
        queued.setCreatedBy("tester");
        queued.setCreateTime(LocalDateTime.now().withNano(0));
        taskMapper.insert(queued);

        NlpTask task = awaitTerminal(queued.getId());

        assertEquals(NlpTask.COMPLETED, task.getStatus());
        assertEquals(1, task.getSuccess());
        assertEquals(0, task.getFailed());
        // 仅归一绝不调 NLP 抽取 —— 这是它省时的根本
        verify(nlpClient, org.mockito.Mockito.never()).extract(anyString());
        // 必须走归一（对已有结构化数据）
        verify(entityNormalizer).normalizeMap(org.mockito.ArgumentMatchers.anyMap(), anyString());
        // 词典版本戳被重打成当前生效版本
        String json = recordMapper.selectById(id).getStructuredData();
        assertNotNull(json);
        assertTrue(json.contains("dict-v1"), "仅归一后应重打当前词典版本戳：" + json);
    }

    private String insertRecord(String orgId, String chiefComplaint) {
        Record record = new Record();
        record.setId(UUID.randomUUID().toString());
        record.setOrgId(orgId);
        record.setChiefComplaint(chiefComplaint);
        record.setVisitTime(LocalDateTime.now().withNano(0));
        record.setStatus("pending");
        recordMapper.insert(record);
        return record.getId();
    }

    private String insertRecordWithStructured(String orgId, String structuredData) {
        Record record = new Record();
        record.setId(UUID.randomUUID().toString());
        record.setOrgId(orgId);
        record.setChiefComplaint("主诉：仅归一");
        record.setVisitTime(LocalDateTime.now().withNano(0));
        record.setStatus("pending");
        record.setStructuredData(structuredData);
        recordMapper.insert(record);
        return record.getId();
    }

    private NlpTask awaitTerminal(String taskId) {
        long deadline = System.currentTimeMillis() + 15_000L;
        NlpTask task = null;
        while (System.currentTimeMillis() < deadline) {
            task = taskMapper.selectById(taskId);
            if (task != null && TERMINAL.contains(task.getStatus())) {
                return task;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("任务未在 15s 内到达终态，最后状态="
                + (task == null ? "null" : task.getStatus()));
    }

    /**
     * 最小测试上下文：不启用 Boot 自动配置，只装配被测链路真正需要的 Bean。
     *
     * <p>自动配置会把 Redis / ES / Spring AI / Web 一并拉起（都在依赖里），
     * 那些都不是被测对象，却会让测试在离线或没有这些服务时失败。</p>
     */
    @Configuration
    @MapperScan(basePackages = "com.tcm.ehr.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
    @Import({NlpBatchServiceImpl.class, AsyncConfig.class})
    static class TestConfig {

        /** H2 内存库；建表脚本只在容器启动时执行一次 */
        @Bean
        public DataSource dataSource() throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(JDBC_URL, "sa", "");
            dataSource.setDriverClassName("org.h2.Driver");
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
            }
            return dataSource;
        }

        /** MyBatis-Plus 的 SqlSessionFactory：不下放自动配置，直接手工建（含 BaseMapper 注入器） */
        @Bean
        public MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory;
        }

        /** 真实 Jackson，不是 mock：词典版本戳的读写要靠它 */
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * 批次 16.1：提交互斥是构造器注入的 DistLock（Redisson）。
         *
         * <p>本测试只走 {@code submitIds}（导入后自动解析路径），不经过 {@code submit} 的锁，
         * 所以给一个「永远拿得到锁」的 RLock 替身即可 —— 不需要真的 Redis。</p>
         */
        @Bean
        public DistLock distLock() {
            RedissonClient redisson = org.mockito.Mockito.mock(RedissonClient.class);
            RLock lock = org.mockito.Mockito.mock(RLock.class);
            org.mockito.Mockito.when(redisson.getLock(org.mockito.ArgumentMatchers.anyString())).thenReturn(lock);
            org.mockito.Mockito.when(lock.isHeldByCurrentThread()).thenReturn(true);
            try {
                org.mockito.Mockito.when(lock.tryLock(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class))).thenReturn(true);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return new DistLock(redisson);
        }
    }
}
