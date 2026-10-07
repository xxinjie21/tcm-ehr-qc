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
    @DisplayName("证候筛选下沉到 SQL 并集（A8）：导出不再内存二次筛，行数==预览 total")
    void patternGoesToSqlUnionAndExportMatchesPreview() throws Exception {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        List<Record> rows = List.of(rec("a", t, null), rec("b", t.plusSeconds(1), null), rec("c", t.plusSeconds(2), null));
        when(mapper.selectList(any())).thenReturn(rows);

        ExportDTO dto = csv();
        dto.setFilters(Map.of("pattern", "肝郁"));

        IGovernanceService.ExportedFile file = svc.export(dto);
        // SQL 已把 pattern 并集筛完，批内不再二次过滤 → 直接导出 SQL 返回的所有行
        assertEquals(3, csvDataLines(file));

        // 关键：传给 selectList 的 wrapper 必须带上「pattern OR structured_data」并集条件
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Record>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper, org.mockito.Mockito.atLeast(1)).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("pattern") && sql.contains("structured_data"),
                "证候筛选必须走 pattern OR structured_data（A8 并集）: " + sql);

        Map<String, Object> preview = svc.previewDataset(dto);
        assertEquals(3L, preview.get("total"), "预览 total == 导出行数（与列表同一筛选条件）");
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