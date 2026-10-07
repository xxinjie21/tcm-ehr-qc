package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tcm.ehr.common.utils.EsTermNormalizer;
import com.tcm.ehr.domain.dto.ExportDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.mapper.ReviewTaskMapper;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IGovernanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A7（性能审查 P1-7）：导出/预览流式 keyset 分批。
 *
 * <p>锁三点：① 分批扫描第一页无锚点、后续页带 {@code (visit_time,id)} 锚点且每页
 * {@code LIMIT 1000}（复用 {@code RecordKeyset}，与 A1 同构）；② 证候筛选在<b>批内</b>
 * 做内存筛，导出行数与筛选口径一致；③ 导出数据行数 == 预览 {@code total}（报告要求的
 * 「导出行数 == 预览 total 自检」）。</p>
 */
@DisplayName("GovernanceServiceImpl：导出/预览流式分批（A7）")
class GovernanceServiceImplExportTest {

    private static final String ORG = "org-export-1";

    private final ReviewTaskMapper reviewTaskMapper = mock(ReviewTaskMapper.class);
    private RecordMapper mapper = mock(RecordMapper.class);
    private GovernanceServiceImpl svc;

    @BeforeEach
    void setUp() {
        mapper = mock(RecordMapper.class);
        svc = new GovernanceServiceImpl(
                mock(EsTermNormalizer.class), new ObjectMapper(), mock(IDictionaryTermStore.class), reviewTaskMapper);
        for (Class<?> c = svc.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(svc, mapper);
                break;
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
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

    private static Record rec(String id, LocalDateTime vt, String patternTerms) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId(ORG);
        r.setVisitTime(vt);
        r.setPattern(patternTerms == null ? null : patternTerms);
        return r;
    }

    private static ExportDTO csv() {
        ExportDTO dto = new ExportDTO();
        dto.setFormat("csv");
        return dto;
    }

    private static long csvDataLines(IGovernanceService.ExportedFile f) {
        String text = new String(f.content(), StandardCharsets.UTF_8);
        long data = text.lines().count() - 1; // 首行是表头
        return Math.max(data, 0);
    }

    @Test
    @DisplayName("分批扫描：第一页无锚点，第二页带 (visit_time,id) 锚点，每页 LIMIT 1000")
    void scanPagesApplyKeysetAnchorAndLimit() throws Exception {
        LocalDateTime t0 = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        List<Record> page1 = new ArrayList<>();
        for (int i = 0; i < 1000; i++) page1.add(rec("p1-" + i, t0.plusSeconds(2000 - i), null));
        List<Record> page2 = new ArrayList<>();
        for (int i = 0; i < 50; i++) page2.add(rec("p2-" + i, t0.plusSeconds(999 - i), null));
        when(mapper.selectList(any())).thenReturn(page1, page2);

        IGovernanceService.ExportedFile file = svc.export(csv());
        assertEquals(1050, csvDataLines(file), "两批 1000+50 条应全部导出（不漏行）");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, times(2)).selectList(captor.capture());
        List<QueryWrapper<Record>> wrappers = captor.getAllValues();
        assertEquals(2, wrappers.size());
        // 第一页：无游标条件，但要有 LIMIT 1000
        assertTrue(!wrappers.get(0).getCustomSqlSegment().contains("visit_time <"),
                "首页不应带游标锚点: " + wrappers.get(0).getCustomSqlSegment());
        assertTrue(wrappers.get(0).getCustomSqlSegment().contains("LIMIT 1000"),
                "每页必须 LIMIT 1000: " + wrappers.get(0).getCustomSqlSegment());
        // 第二页：带 (visit_time, id) 锚点
        String second = wrappers.get(1).getCustomSqlSegment();
        assertTrue(second.contains("visit_time <"), "第二页应带 visit_time 锚点: " + second);
        assertTrue(second.contains("id >"), "第二页应带 id 锚点: " + second);
    }

    @Test
    @DisplayName("证候筛选在批内做内存筛，预览 total 与导出行数一致（< 1000 一页）")
    void patternFilterWithinBatchAndPreviewTotalMatchesExportRows() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        // structured_patternContains 走 JSON 的 patternList；为让测试可构造，用原始列回退不可达 →
        // 这里直接给 JSON：content 含「肝郁」的两条命中，另一条不命中
        Record hit1 = rec("h1", t, null);
        hit1.setStructuredData("{\"patternList\":[{\"content\":\"肝郁气滞\"}]}");
        Record miss = rec("m1", t, null);
        miss.setStructuredData("{\"patternList\":[{\"content\":\"脾虚\"}]}");
        Record hit2 = rec("h2", t.plusSeconds(1), null);
        hit2.setStructuredData("{\"patternList\":[{\"content\":\"肝郁化火\"}]}");
        when(mapper.selectList(any())).thenReturn(List.of(hit1, miss, hit2));

        ExportDTO dto = csv();
        dto.setFilters(Map.of("pattern", "肝郁"));

        IGovernanceService.ExportedFile file = svc.export(dto);
        assertEquals(2, csvDataLines(file), "带证候筛选时应只导出命中的 2 条");

        Map<String, Object> preview = svc.previewDataset(dto);
        assertEquals(2L, preview.get("total"), "预览 total == 导出行数（口径一致）");
        @SuppressWarnings("unchecked")
        List<?> sample = (List<?>) preview.get("sample");
        assertEquals(2, sample.size());
    }

    @Test
    @DisplayName("无证候筛选：行数 == 预览 total（count 与 selectList 同 wrapper）")
    void exportRowCountMatchesPreviewTotalWithoutPattern() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        when(mapper.selectCount(any())).thenReturn(3L);
        when(mapper.selectList(any())).thenReturn(List.of(
                rec("a", t, null), rec("b", t.plusSeconds(1), null), rec("c", t.plusSeconds(2), null)));

        IGovernanceService.ExportedFile file = svc.export(csv());
        assertEquals(3, csvDataLines(file));
        Map<String, Object> preview = svc.previewDataset(csv());
        assertEquals(3L, preview.get("total"));
        assertEquals(3, ((List<?>) preview.get("sample")).size());
    }
}