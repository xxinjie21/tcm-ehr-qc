package com.tcm.ehr.common.utils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tcm.ehr.domain.po.NlpTaskItem;
import com.tcm.ehr.mapper.NlpTaskItemMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link BatchInsert} 的事务语义守卫（P2-8 前置：确认批写不改原子性）。
 *
 * <p><b>为什么必须有这条</b>：批写走的是自己开的 {@code ExecutorType.BATCH} 会话，
 * 一旦它拿到的是<b>独立连接 / 独立事务</b>，就会出现最危险的一类缺陷 —— 外层业务事务
 * 回滚了，批插的行却已经落库；而且单测用 mock 完全测不出来（Mapper 全被 stub）。
 * 这里用 H2 真库把两件事钉死：</p>
 * <ol>
 *   <li><b>回滚传导</b>：外层事务 rollback → 批插的行必须一并消失；</li>
 *   <li><b>提交传导</b>：外层事务 commit → 批插的行必须在（证明批写会话里的
 *       {@code commit()} 空操作没有把外层提交也吃掉）；</li>
 *   <li><b>异常不被吞</b>：批写中途失败必须向上抛，且此前已 flush 的行整体回滚 ——
 *       与「循环逐条 insert 第 k 条抛错」的原子性一致，唯一键 / 非空冲突不会被静默忽略。</li>
 * </ol>
 */
@SpringJUnitConfig(BatchInsertTransactionTest.TestConfig.class)
class BatchInsertTransactionTest {

    /** 独立库名：与 NlpBatchIntegrationTest 的 mem:nlp_it 隔开，互不干扰 */
    private static final String JDBC_URL =
            "jdbc:h2:mem:batch_insert_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    @Autowired
    private NlpTaskItemMapper itemMapper;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        itemMapper.delete(new QueryWrapper<>());
    }

    @Test
    void rollbackOfOuterTransactionAlsoRollsBackBatchInsertedRows() {
        tx.executeWithoutResult(status -> {
            BatchInsert.insert(sqlSessionFactory, NlpTaskItemMapper.class, itemMapper, items("t-rb", 500));
            // 同一事务内应立刻可见（批写会话与普通 Mapper 共用事务连接）
            assertEquals(500, countOf("t-rb"), "事务内应看到批插的 500 行");
            status.setRollbackOnly();
        });
        assertEquals(0, countOf("t-rb"),
                "外层事务回滚后批插的行必须一并消失；若残留 500 行，说明批写用了独立连接 / 独立事务");
    }

    @Test
    void commitOfOuterTransactionAlsoCommitsBatchInsertedRows() {
        tx.executeWithoutResult(status ->
                BatchInsert.insert(sqlSessionFactory, NlpTaskItemMapper.class, itemMapper, items("t-ok", 3)));
        assertEquals(3, countOf("t-ok"),
                "外层事务提交后批插的行必须在；否则批写会话里的 commit() 把外层提交吃掉了");
    }

    @Test
    void failureDuringBatchPropagatesAndRollsBackTheWholeBatch() {
        List<NlpTaskItem> rows = new ArrayList<>(items("t-bad", 2));
        // 第二条 task_id 置空：task_id 是 NOT NULL，插入必失败
        rows.get(1).setTaskId(null);

        assertThrows(Exception.class, () ->
                tx.executeWithoutResult(status ->
                        BatchInsert.insert(sqlSessionFactory, NlpTaskItemMapper.class, itemMapper, rows)),
                "批写失败必须向上抛，不能被吞成静默跳过");

        assertEquals(0, countOf("t-bad"),
                "批写中途失败必须整体回滚，不能留下已 flush 的前半批");
    }

    @Test
    void nullFactoryFallsBackToPerRowInsert() {
        // 单测直接 new 服务类时没有 SqlSessionFactory，必须退化为逐条写且语义不变
        BatchInsert.insert(null, NlpTaskItemMapper.class, itemMapper, items("t-fb", 3));
        assertEquals(3, countOf("t-fb"));
    }

    @Test
    void moreThanOneBatchStillLandsCompletely() {
        // 1200 条 > BATCH_SIZE(500)：跨批 flush 后行数必须一条不少
        tx.executeWithoutResult(status ->
                BatchInsert.insert(sqlSessionFactory, NlpTaskItemMapper.class, itemMapper, items("t-multi", 1200)));
        assertEquals(1200, countOf("t-multi"), "跨多批 flush 后不能丢尾批");
    }

    @Test
    void batchInsertWorksAfterANormalMapperCallInTheSameTransaction() {
        // 正是 DictionaryTermStoreImpl.replace 的形态：同一事务里先普通 Mapper（delete / 查询，
        // 会把 SIMPLE 会话绑到本事务），随后才批写。若批写会话与普通会话在事务内互斥
        // （executorType 冲突），这里会直接抛异常 —— 钉住「先普通后批写」这条路可行。
        tx.executeWithoutResult(status -> {
            itemMapper.delete(new QueryWrapper<NlpTaskItem>().eq("task_id", "t-pre"));
            BatchInsert.insert(sqlSessionFactory, NlpTaskItemMapper.class, itemMapper, items("t-pre", 3));
            assertEquals(3, countOf("t-pre"), "同一事务内先普通查询再批写必须可行且立即可见");
        });
        assertEquals(3, countOf("t-pre"), "外层提交后仍在");
    }

    private long countOf(String taskId) {
        return itemMapper.selectCount(new QueryWrapper<NlpTaskItem>().eq("task_id", taskId));
    }

    private static List<NlpTaskItem> items(String taskId, int n) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<NlpTaskItem> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            NlpTaskItem item = new NlpTaskItem();
            item.setTaskId(taskId);
            item.setRecordId("rec-" + i);
            item.setSeq(i);
            item.setStatus(NlpTaskItem.PENDING);
            item.setCreateTime(now);
            item.setUpdateTime(now);
            rows.add(item);
        }
        return rows;
    }

    /**
     * 最小上下文：H2（MODE=MySQL）+ Mapper 扫描 + 真实 {@code SqlSessionFactory} +
     * {@code DataSourceTransactionManager}（本测试要的正是「Spring 管理的事务」）。
     */
    @Configuration
    @MapperScan(basePackages = "com.tcm.ehr.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
    static class TestConfig {

        @Bean
        public DataSource dataSource() throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(JDBC_URL, "sa", "");
            dataSource.setDriverClassName("org.h2.Driver");
            try (Connection connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema-h2.sql"));
            }
            return dataSource;
        }

        @Bean
        public MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory;
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
