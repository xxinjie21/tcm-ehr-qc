package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.tcm.ehr.domain.dto.DictProposalDTOs;
import com.tcm.ehr.domain.po.DictArchiveTerm;
import com.tcm.ehr.domain.po.DictArchiveVersion;
import com.tcm.ehr.domain.po.DictProposal;
import com.tcm.ehr.domain.po.DictProposalTerm;
import com.tcm.ehr.domain.vo.DictProposalVO;
import com.tcm.ehr.mapper.DictArchiveTermMapper;
import com.tcm.ehr.mapper.DictArchiveVersionMapper;
import com.tcm.ehr.mapper.DictProposalMapper;
import com.tcm.ehr.mapper.DictProposalTermMapper;
import com.tcm.ehr.service.IDictArchiveService;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-7「列表计数由 N 次 selectCount 改成一次 GROUP BY」的取值守卫。
 *
 * <p><b>为什么要测</b>：改造后计数的取值依赖结果 Map 的键（{@code proposal_id} / {@code version_id}
 * 与别名 {@code cnt}）。取错键不会报错，只会让计数恒为 0 —— 表现为
 * {@code DictProposalVO.termCount=0 / snapshotPresent=false}：界面显示「快照已清理」、
 * 回滚被拒。这正是「测试全绿、功能静默坏掉」的形态，必须用真库钉住。</p>
 *
 * <p>覆盖三种取值：无快照（0）、单条、多条，并同时覆盖「多个版本 / 多个提案」这条
 * 批量分支（原先各自是一次独立查询）。</p>
 */
@SpringJUnitConfig(DictSnapshotCountBatchTest.TestConfig.class)
class DictSnapshotCountBatchTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:dict_count_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    private static final String ORG = "org-1";
    private static final String TYPE = "symptom";

    @Autowired
    private DictArchiveVersionMapper versionMapper;

    @Autowired
    private DictArchiveTermMapper archiveTermMapper;

    @Autowired
    private DictProposalMapper proposalMapper;

    @Autowired
    private DictProposalTermMapper proposalTermMapper;

    @MockitoBean
    private IDictionaryTermStore termStore;

    @MockitoBean
    private IDictArchiveService archiveService;

    @MockitoBean
    private IEsTermIndexService esIndexService;

    private DictArchiveServiceImpl archiveSvc;
    private DictProposalServiceImpl proposalService;

    @BeforeEach
    void setUp() {
        archiveTermMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        versionMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        proposalTermMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        proposalMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());

        // 直接 new：SqlSessionFactory 未装配 → 走逐条写退化路径，本测试只关心读取侧的计数
        archiveSvc = new DictArchiveServiceImpl(versionMapper, archiveTermMapper, new ObjectMapper());
        proposalService = new DictProposalServiceImpl(proposalMapper, proposalTermMapper, termStore,
                archiveService, esIndexService, new ObjectMapper(), null, null);
    }

    @Test
    void archiveListCountsTermsPerVersionInOnePass() {
        String v1 = newVersion(1);
        String v2 = newVersion(2);
        String v3 = newVersion(3);
        addArchiveTerms(v1, 3);
        addArchiveTerms(v2, 1);
        // v3 故意不给快照：代表已被 5 份限额清理，应得 0 / snapshotPresent=false

        List<DictArchiveVersion> out = archiveSvc.list(ORG, TYPE);
        assertEquals(3, out.size());

        // 版本倒序
        assertEquals(3, out.get(0).getVersionNo());
        assertEquals(0, out.get(0).getTermCount(), "无快照的版本计数必须是 0");
        assertFalse(out.get(0).getSnapshotPresent(), "无快照的版本 snapshotPresent 必须是 false");

        assertEquals(2, out.get(1).getVersionNo());
        assertEquals(1, out.get(1).getTermCount());
        assertTrue(out.get(1).getSnapshotPresent());

        assertEquals(1, out.get(2).getVersionNo());
        assertEquals(3, out.get(2).getTermCount());
        assertTrue(out.get(2).getSnapshotPresent());
    }

    @Test
    void proposalListCountsSnapshotTermsPerProposal() {
        String p1 = newProposal(DictProposal.PENDING);
        String p2 = newProposal(DictProposal.PENDING);
        String p3 = newProposal(DictProposal.PENDING);
        addProposalTerms(p1, 4);
        addProposalTerms(p2, 1);
        // p3 不给快照：代表已被惰性清理，应得 0 / snapshotPresent=false

        DictProposalDTOs.ProposalQuery query = new DictProposalDTOs.ProposalQuery();
        query.setOrgId(ORG);
        query.setType(TYPE);
        query.setIsOwner(true);

        List<DictProposalVO> out = proposalService.list(query);
        assertEquals(3, out.size());

        java.util.Map<String, Integer> byId = new java.util.HashMap<>();
        java.util.Map<String, Boolean> present = new java.util.HashMap<>();
        for (DictProposalVO vo : out) {
            byId.put(vo.getId(), vo.getTermCount());
            present.put(vo.getId(), vo.getSnapshotPresent());
        }
        assertEquals(4, byId.get(p1));
        assertEquals(1, byId.get(p2));
        assertEquals(0, byId.get(p3), "快照被清理的提案计数必须是 0");
        assertFalse(present.get(p3), "快照被清理的提案 snapshotPresent 必须是 false");
        assertTrue(present.get(p1));
    }

    private String newVersion(int versionNo) {
        DictArchiveVersion v = new DictArchiveVersion();
        v.setId(UUID.randomUUID().toString());
        v.setOrgId(ORG);
        v.setType(TYPE);
        v.setVersionNo(versionNo);
        v.setMergeTime(LocalDateTime.now().withNano(0));
        versionMapper.insert(v);
        return v.getId();
    }

    private void addArchiveTerms(String versionId, int n) {
        for (int i = 0; i < n; i++) {
            DictArchiveTerm t = new DictArchiveTerm();
            t.setId(UUID.randomUUID().toString());
            t.setVersionId(versionId);
            t.setStandardTerm("词-" + versionId + "-" + i);
            t.setAliases("[]");
            archiveTermMapper.insert(t);
        }
    }

    private String newProposal(String status) {
        DictProposal p = new DictProposal();
        p.setId(UUID.randomUUID().toString());
        p.setOrgId(ORG);
        p.setType(TYPE);
        p.setSubmitUserId("u-1");
        p.setStatus(status);
        p.setCreateTime(LocalDateTime.now().withNano(0));
        proposalMapper.insert(p);
        return p.getId();
    }

    private void addProposalTerms(String proposalId, int n) {
        for (int i = 0; i < n; i++) {
            DictProposalTerm t = new DictProposalTerm();
            t.setId(UUID.randomUUID().toString());
            t.setProposalId(proposalId);
            t.setStandardTerm("词-" + proposalId + "-" + i);
            t.setAliases("[]");
            proposalTermMapper.insert(t);
        }
    }

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
    }
}
