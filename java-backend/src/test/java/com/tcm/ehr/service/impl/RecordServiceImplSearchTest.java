package com.tcm.ehr.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tcm.ehr.domain.dto.SearchDTO;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.SearchVO;
import com.tcm.ehr.mapper.RecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分页检索病历列表（批次 13 第一阶段收尾）。
 *
 * <p>它是 7 个公开方法里**最后未被覆盖**的一个（另六个由 RecordImportExcelTest /
 * RecordImportLargeFileTest / RecordOriginalFieldsReadOnlyTest 与本轮新增的三个类覆盖）。
 * 原先在缺口清单里判断它「需 ES 替身」—— 读过实现后发现**是错的**：它走 MyBatis-Plus 分页，
 * 与 ES 无关，用同一套替身即可测。</p>
 *
 * <p>锁三件事：① 分页参数非法时回退 1 页 / 每页 20 条（不是抛错、也不是 0 条）；
 * ② 列表摘要的回退顺序（主诉 → 中医诊断 → 西医诊断）与 40 字截断；
 * ③ {@code manuallyEdited} 读 {@code manually_edited} 标量列（性能审查 P1-2#2 落列后，
 * 列表不再解析 {@code structured_data}）。</p>
 */
class RecordServiceImplSearchTest {

    private static final String TITLE_40 = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";

    private static RecordServiceImpl svc(RecordMapper mapper) {
        RecordServiceImpl s = new RecordServiceImpl(
                new tools.jackson.databind.ObjectMapper(),   // 真实解析器：_meta 判定要真的解析 JSON
                mock(com.tcm.ehr.service.INlpBatchService.class),
                mock(com.tcm.ehr.mapper.ReviewTaskMapper.class),
                mock(com.tcm.ehr.service.IDictionaryTermStore.class));
        for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("baseMapper");
                f.setAccessible(true);
                f.set(s, mapper);
                break;
            } catch (NoSuchFieldException ignored) {
                // 往父类继续找
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return s;
    }

    private static void context() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentUserId", "u-1");
        req.setAttribute("currentOrgId", "org-A");
        req.setAttribute("currentOrgRole", "member");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Record record(String id) {
        Record r = new Record();
        r.setId(id);
        r.setOrgId("org-A");
        return r;
    }

    /** 让 selectPage 回一个固定页，并捕获调用方传进去的 Page */
    private static ArgumentCaptor<Page<Record>> stubPage(RecordMapper mapper, java.util.List<Record> rows, long total) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Page<Record>> captor = ArgumentCaptor.forClass(Page.class);
        when(mapper.selectPage(captor.capture(), any())).thenAnswer(inv -> {
            Page<Record> p = inv.getArgument(0);
            p.setRecords(rows);
            p.setTotal(total);
            return p;
        });
        return captor;
    }

    @Test
    @DisplayName("分页参数非法 ⇒ 回退 1 页 / 每页 20 条（不报错、也不返回 0 条）")
    void pagingFallsBackToDefaults() {
        RecordMapper mapper = mock(RecordMapper.class);
        ArgumentCaptor<Page<Record>> captor = stubPage(mapper, java.util.List.of(record("r1")), 1);
        context();
        try {
            SearchDTO dto = new SearchDTO();
            dto.setPage(0);
            dto.setPageSize(-5);
            svc(mapper).searchRecords(dto);

            Page<Record> used = captor.getValue();
            assertEquals(1, used.getCurrent(), "非法页码要回退到第 1 页");
            assertEquals(20, used.getSize(), "非法每页条数要回退到 20");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("合法分页原样下推，总数原样回传")
    void pagingIsPassedThrough() {
        RecordMapper mapper = mock(RecordMapper.class);
        ArgumentCaptor<Page<Record>> captor = stubPage(mapper, java.util.List.of(record("r1")), 137);
        context();
        try {
            SearchDTO dto = new SearchDTO();
            dto.setPage(3);
            dto.setPageSize(50);
            SearchVO vo = svc(mapper).searchRecords(dto);

            assertEquals(3, captor.getValue().getCurrent());
            assertEquals(50, captor.getValue().getSize());
            assertEquals(137, vo.getTotal(), "总数要原样回传（分页器靠它算页数）");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("摘要按「主诉 → 中医诊断 → 西医诊断 → 空串」回退，且超过 40 字截断加省略号")
    void summaryFallsBackAndTruncates() {
        RecordMapper mapper = mock(RecordMapper.class);
        Record byChief = record("r1");
        byChief.setChiefComplaint(TITLE_40 + "多出来的部分");
        Record byTcm = record("r2");
        byTcm.setTcmDiagnosis("眩晕");
        Record byWestern = record("r3");
        byWestern.setWesternDiagnosis("高血压");
        Record empty = record("r4");
        stubPage(mapper, java.util.List.of(byChief, byTcm, byWestern, empty), 4);
        context();
        try {
            SearchVO vo = svc(mapper).searchRecords(null);

            assertEquals(40 + 1, vo.getRecords().get(0).getSummary().length(),
                    "超长主诉截到 40 字再加一个省略号");
            assertTrue(vo.getRecords().get(0).getSummary().endsWith("…"));
            assertEquals("眩晕", vo.getRecords().get(1).getSummary(), "主诉缺 → 回退中医诊断");
            assertEquals("高血压", vo.getRecords().get(2).getSummary(), "中医诊断也缺 → 回退西医诊断");
            assertEquals("", vo.getRecords().get(3).getSummary(), "三处都空 → 空串，不是 null");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("manuallyEdited 读标量列：列表不再解析 structured_data（P1-2#2）")
    void manualFlagComesFromColumn() {
        RecordMapper mapper = mock(RecordMapper.class);
        // 1. 列 = 1 → 标出来（structured_data 为空也要标）
        Record manual = record("r1");
        manual.setManuallyEdited(true);
        // 2. 列 = 0 → 不标。这里**故意**让 JSON 里带 _meta.manuallyEdited:true：
        //    若实现回退成解析 JSON，这条会被误标，测试即失败 —— 用它锁住「读取源是列」
        Record model = record("r2");
        model.setStructuredData("{\"_meta\":{\"manuallyEdited\":true},\"symptoms\":[]}");
        model.setManuallyEdited(false);
        stubPage(mapper, java.util.List.of(manual, model), 2);
        context();
        try {
            SearchVO vo = svc(mapper).searchRecords(null);
            assertTrue(vo.getRecords().get(0).getManuallyEdited(), "列 = 1 的要标出来");
            assertFalse(vo.getRecords().get(1).getManuallyEdited(),
                    "列表读 manually_edited 列，不再解析 structured_data：列 = 0 就不标");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
