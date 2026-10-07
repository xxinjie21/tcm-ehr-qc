package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5（性能审查 P1-6）：三个视图拆 All/Org 后的**真 SQL 数据域守卫**。
 *
 * <p>全仓 {@code @Select} 的映射级断言此前只能靠人眼（NlpBatchIntegrationTest 是全仓唯一
 * 真 SQL 集成测试）。这里用 H2 把 {@code selectOverview / selectGovernanceStats /
 * selectDepartments} 的每一条分支钉死，重点是 <b>fail-closed</b>：</p>
 *
 * <ul>
 *   <li>All 分支：跨机构聚合（仅管理员路径调用）；</li>
 *   <li>Org 分支：只统计本机构，且 {@code orgId} 传空串 / null 时 <b>必须 0 行</b>
 *       —— 这是 A5 唯一能防「普通成员看到别组数据」而不报错、不编译失败的断言。</li>
 * </ul>
 */
@DisplayName("RecordMapper：视图 SQL 数据域守卫（A5）")
@SpringJUnitConfig(RecordMapperScopeIT.TestConfig.class)
class RecordMapperScopeIT {

    private static final String ORG1 = "org-scope-1";
    private static final String ORG2 = "org-scope-2";

    @Autowired
    private RecordMapper mapper;

    @BeforeEach
    void seed() {
        mapper.delete(null);
        // org1：合格·已清洗 / 合格·待清洗 / 待复核   （科室内科 / 骨科）
        insert(ORG1, "合格", 1, "内科");
        insert(ORG1, "合格", 0, "骨科");
        insert(ORG1, "待复核", 0, "内科");
        // org2：无效（科室外科）
        insert(ORG2, "无效", 0, "外科");
    }

    @AfterEach
    void clear() {
        mapper.delete(null);
    }

    private void insert(String orgId, String grade, int governed, String department) {
        Record r = new Record();
        r.setId(UUID.randomUUID().toString());
        r.setOrgId(orgId);
        r.setGrade(grade);
        r.setDepartment(department);
        r.setVisitTime(LocalDateTime.now().withNano(0));
        mapper.insert(r);
        // Record 实体未映射 governed（仅 SQL 写路径消费），用现成的 markGoverned 置位
        if (governed == 1) {
            mapper.markGoverned(r.getId());
        }
    }

    private static long num(Map<String, Object> row, String key) {
        // H2（MODE=MySQL + DATABASE_TO_LOWER=TRUE）会把未加引号的别名转小写，
        // 生产 MySQL 保留原样 —— 这里大小写不敏感取值，两环境通用
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) {
                return ((Number) e.getValue()).longValue();
            }
        }
        throw new AssertionError("聚合结果缺少列 " + key + "：" + row);
    }

    private static boolean has(Map<String, Object> row, String key) {
        for (String k : row.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void overviewAllSeesAllOrgsOverviewOrgOnlyOwn() {
        Map<String, Object> all = mapper.selectOverviewAll();
        assertEquals(4, num(all, "totalRecords"));
        assertEquals(2, num(all, "qualifiedCount"));

        Map<String, Object> org1 = mapper.selectOverviewOrg(ORG1);
        assertEquals(3, num(org1, "totalRecords"));
        assertEquals(2, num(org1, "qualifiedCount"));
        assertEquals(1, num(org1, "pendingReviewCount"));

        Map<String, Object> org2 = mapper.selectOverviewOrg(ORG2);
        assertEquals(1, num(org2, "totalRecords"));
        assertEquals(0, num(org2, "qualifiedCount"));
        assertEquals(1, num(org2, "invalidCount"));
    }

    @Test
    void overviewOrgBlankOrNullIsFailClosedZeroRows() {
        // ⚠️ fail-closed：orgId 为空必须「查不到任何数据」，绝不能退化成全表
        assertEquals(0, num(mapper.selectOverviewOrg(""), "totalRecords"),
                "空 orgId 必须 0 行（跨机构越权防线）");
        assertEquals(0, num(mapper.selectOverviewOrg(null), "totalRecords"),
                "null orgId 必须 0 行（跨机构越权防线）");
    }

    @Test
    void governanceStatsAllVsOrgAndFailClosed() {
        Map<String, Object> all = mapper.selectGovernanceStatsAll();
        assertEquals(2, num(all, "qualified"));          // org1 两行合格
        assertEquals(1, num(all, "governedCount"));      // 合格且已清洗 = org1「内科」一行
        assertEquals(1, num(all, "pendingGovern"));

        Map<String, Object> org1 = mapper.selectGovernanceStatsOrg(ORG1);
        assertEquals(2, num(org1, "qualified"));
        assertEquals(1, num(org1, "governedCount"));

        assertEquals(0, num(mapper.selectGovernanceStatsOrg(""), "qualified"));
        assertEquals(0, num(mapper.selectGovernanceStatsOrg(null), "qualified"));
    }

    @Test
    void departmentsAllVsOrgAndFailClosed() {
        List<String> all = mapper.selectDepartmentsAll();
        assertTrue(all.containsAll(List.of("内科", "骨科", "外科")), "All = 全部机构科室: " + all);

        List<String> org1 = mapper.selectDepartmentsOrg(ORG1);
        assertTrue(org1.containsAll(List.of("内科", "骨科")));
        assertTrue(!org1.contains("外科"), "Org 分支不得泄漏别组科室: " + org1);

        assertTrue(mapper.selectDepartmentsOrg("").isEmpty(), "空 orgId 不得返回任何科室");
        assertTrue(mapper.selectDepartmentsOrg(null).isEmpty(), "null orgId 不得返回任何科室");
    }

    @Test
    void columnShapeIsIntact() {
        // 防拆 SQL 时改错别名：三个视图的字段名必须保持前端已消费的名称
        Map<String, Object> row = mapper.selectOverviewOrg(ORG1);
        assertTrue(!has(row, "qualified"), "overview 不应混入 governance 的别名");
        assertTrue(has(row, "totalRecords"));
        assertTrue(has(row, "qualifiedCount"));
        Map<String, Object> gov = mapper.selectGovernanceStatsOrg(ORG1);
        assertTrue(has(gov, "qualified"));
        assertTrue(has(gov, "governedCount"));
        assertTrue(has(gov, "pendingGovern"));
    }

    /** 最小上下文：只装配 Mapper + 数据源，不拉起 Redis/ES/Web */
    @Configuration
    @MapperScan(basePackages = "com.tcm.ehr.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
    static class TestConfig {

        private static final String JDBC_URL =
                "jdbc:h2:mem:scope_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

        @Bean
        public DataSource dataSource() throws Exception {
            DriverManagerDataSource ds = new DriverManagerDataSource(JDBC_URL, "sa", "");
            ds.setDriverClassName("org.h2.Driver");
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new ClassPathResource("schema-h2.sql"));
            }
            return ds;
        }

        @Bean
        public MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            return factory;
        }
    }
}