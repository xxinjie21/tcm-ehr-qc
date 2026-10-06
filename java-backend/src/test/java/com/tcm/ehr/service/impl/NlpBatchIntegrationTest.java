package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
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
import com.tcm.ehr.service.DictionaryTermStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * 词典版本（DictionaryTermStore）都不是本链路的被测对象，网络与 ES 依赖会引入不确定性。</p>
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

    @Autowired
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
    private DictionaryTermStore termStore;

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
    @Import(NlpBatchServiceImpl.class)
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
    }
}
