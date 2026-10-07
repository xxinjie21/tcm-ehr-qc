package com.tcm.ehr.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tcm.ehr.common.utils.RecordFilter;
import com.tcm.ehr.domain.dto.FiltersDTO;
import com.tcm.ehr.domain.dto.SearchDTO;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A8（统一证候筛选口径）：{@code pattern OR structured_data} 并集过滤的 H2 真 SQL 边界。
 *
 * <p>锁四类边界：① 原始 {@code pattern} 列命中；② 仅 {@code structured_data.patternList}
 * JSON 命中（归一后标准词，旧实现列表会漏）；③ 两边都不命中；④ {@code structured_data} 为
 * NULL。断言：同一筛选条件下，列表（SearchDTO）与统计/删除（FiltersDTO）两个 build 入口
 * 返回<b>同一结果集</b> —— 这是「导出与列表口径一致」的核心前提。</p>
 */
@DisplayName("RecordFilter：证候筛选并集（A8）")
@SpringJUnitConfig(RecordFilterPatternUnionIT.TestConfig.class)
class RecordFilterPatternUnionIT {

    private static final String ORG = "org-pattern-1";
    private static final String TERM = "气滞痰阻证";

    @Autowired
    private RecordMapper mapper;

    private Record r1; // ① 原始列命中
    private Record r2; // ② 仅 JSON 命中
    private Record r3; // ③ 都不命中
    private Record r4; // ④ structured_data=NULL 但原始列命中

    @BeforeEach
    void seed() {
        mapper.delete(null);
        r1 = insert("p1", TERM, "{}");
        r2 = insert("p2", "其他写法", "{\"patternList\":[{\"content\":\"" + TERM + "\"}]}");
        r3 = insert("p3", "脾虚", "{\"patternList\":[{\"content\":\"脾虚\"}]}");
        r4 = insert("p4", TERM, null);
        insert("p5", null, null);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", ORG);
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private Record insert(String id, String pattern, String structured) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId(ORG);
        r.setPattern(pattern);
        r.setStructuredData(structured);
        r.setVisitTime(LocalDateTime.now().withNano(0));
        mapper.insert(r);
        return r;
    }

    private List<String> ids(QueryWrapper<Record> wrapper) {
        return mapper.selectList(wrapper).stream().map(Record::getId).toList();
    }

    @Test
    @DisplayName("FiltersDTO 入口：并集命中 ① 原始列 + ② 仅JSON + ④ NULL尾巴命中原列；③⑤排除")
    void filtersDtoUnionHitsAllRelevantBoundaries() {
        FiltersDTO f = new FiltersDTO();
        f.setPattern(TERM);
        assertEquals(List.of(r1.getId(), r2.getId(), r4.getId()).stream().sorted().toList(),
                ids(RecordFilter.build(ORG, f)).stream().sorted().toList(),
                "并集应命中：原列、仅 JSON、NULL 尾 + 原列三处；排除不命中的两处");
    }

    @Test
    @DisplayName("SearchDTO 入口与 FiltersDTO 返回同一结果集（导出/列表口径一致的前提）")
    void searchDtoMatchesFiltersDtoSemantics() {
        SearchDTO s = new SearchDTO();
        s.setPattern(TERM);
        FiltersDTO f = new FiltersDTO();
        f.setPattern(TERM);
        assertEquals(ids(RecordFilter.build(ORG, f)), ids(RecordFilter.build(ORG, s)),
                "两个 build 入口必须返回同一结果集，否则列表与导出/范围操作再次分叉");
    }

    @Test
    @DisplayName("不命中：两种入口都是空集（并集不会把不相关的行带进来）")
    void nonMatchingTermYieldsEmptyOnBoth() {
        SearchDTO s = new SearchDTO();
        s.setPattern("完全不存在的证候词");
        FiltersDTO f = new FiltersDTO();
        f.setPattern("完全不存在的证候词");
        assertEquals(0, ids(RecordFilter.build(ORG, s)).size());
        assertEquals(0, ids(RecordFilter.build(ORG, f)).size());
    }

    @Configuration
    @MapperScan(basePackages = "com.tcm.ehr.mapper", sqlSessionFactoryRef = "sqlSessionFactory")
    static class TestConfig {

        private static final String JDBC_URL =
                "jdbc:h2:mem:pattern_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

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