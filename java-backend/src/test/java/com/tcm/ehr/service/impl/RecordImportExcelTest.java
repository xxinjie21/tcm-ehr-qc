package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.RequestUtils;
import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.ImportTaskVO;
import com.tcm.ehr.domain.vo.ImportSummaryVO;
import com.tcm.ehr.mapper.RecordMapper;
import com.tcm.ehr.service.DictionaryTermStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病历 Excel 导入的端到端测试（批次 15「改流式读」的硬前置）。
 *
 * <p><b>为什么先补这条测试</b>：这条路径此前<b>零测试</b> —— {@code importRecords} /
 * {@code parseFile} / {@code mapRow} / {@code cellText} 在全仓测试里没有任何引用，
 * 而它承载 21 列映射、日期语义、去重哈希。没有它去改解析路径，等于「看起来跑通」，
 * 真出问题时是在真实导入时才暴露，且不报错（接诊时间整列变 null 就是这么来的）。</p>
 *
 * <p>锁三件事：① 21 个列名都能映射到对应字段；② 「接诊时间」列的紧凑数字串
 * （{@code 20221224090613}，真实导入源的形态）能解析成正确时刻 —— 这条正是流式路径
 * 用 {@code DataFormatter} 会翻车的点（会拿到 {@code 2.02212E+13}）；
 * ③ 入库前带上组织标记与 pending 状态（导入与单条新增同口径）。</p>
 */
class RecordImportExcelTest {

    private RecordMapper recordMapper;
    private RecordServiceImpl svc;

    @BeforeEach
    void setUp() {
        recordMapper = mock(RecordMapper.class);
        // 库里没有同哈希的病历（去重查询走的是 selectList）
        when(recordMapper.selectList(any())).thenReturn(new ArrayList<>());

        DictionaryTermStore termStore = mock(DictionaryTermStore.class);
        svc = spy(new RecordServiceImpl(new ObjectMapper(), null, null, termStore));
        // baseMapper 来自 ServiceImpl 父类，构造器不接，只能反射塞进去
        ReflectionTestUtils.setField(svc, "baseMapper", recordMapper);
        // saveBatch 走 MyBatis-Plus 的 SqlHelper（要真 SqlSession），单测里拦掉、用入参做断言
        doReturn(true).when(svc).saveBatch(any());

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(RequestUtils.ATTR_USER_ID, "u-1");
        req.setAttribute(RequestUtils.ATTR_ROLE, "用户");
        req.setAttribute(RequestUtils.ATTR_ORG_ID, "org-A");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MultipartFile xlsx(String[][] rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("病历");
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i);
                for (int c = 0; c < rows[i].length; c++) {
                    r.createCell(c).setCellValue(rows[i][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("files", "records.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private List<Record> importAndCapture(MultipartFile file) {
        ImportTaskVO task = svc.importRecords(new MultipartFile[]{file}, false);
        assertNotNull(task, "导入应返回任务视图");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Record>> captor = ArgumentCaptor.forClass(Collection.class);
        try {
            verify(svc).saveBatch(captor.capture());
        } catch (AssertionError e) {
            // 没有待入库病历是最难查的失败：把 summary 摊开，失败明细能直接指出解析中断在哪一步
            ImportSummaryVO s = task.getSummary();
            throw new AssertionError("没有产生任何待入库病历；summary=" + (s == null ? "null"
                    : "total=" + s.getTotal() + " failed=" + s.getFailed() + " failures=" + s.getFailures()), e);
        }
        return new ArrayList<>(captor.getValue());
    }

    /** 真实导入源的表头顺序（与 HEADER_FIELD 的 21 个中文列名一致） */
    private static final String[] HEADER = {
            "登记号", "门诊号", "性别", "年龄", "就诊次数", "西医诊断", "中医诊断",
            "现病史", "主诉", "自诉", "望诊", "脉诊", "舌诊", "查体", "辨证结论",
            "草药", "随访", "治疗效果", "开单科室", "医生工号", "接诊时间"};

    @Test
    void mapsAll21ColumnsFromXlsx() throws Exception {
        String[] data = {"REG-1", "OP-1", "男", "45", "2", "高血压", "肝郁气滞",
                "现病史文本", "主诉文本", "自诉文本", "望诊文本", "脉象文本", "舌象文本", "查体文本",
                "肝郁气滞", "柴胡疏肝散", "随访文本", "显效", "中医内科", "D-1", "20221224090613"};

        List<Record> parsed = importAndCapture(xlsx(new String[][]{HEADER, data}));

        assertEquals(1, parsed.size(), "一行数据应产出一条病历");
        Record r = parsed.get(0);
        assertEquals("REG-1", r.getRegistrationNo());
        assertEquals("OP-1", r.getOutpatientNo());
        assertEquals("男", r.getGender());
        assertEquals("45", r.getAge());
        assertEquals(2, r.getVisitCount(), "就诊次数应解析成数字");
        assertEquals("高血压", r.getWesternDiagnosis());
        assertEquals("肝郁气滞", r.getTcmDiagnosis());
        assertEquals("现病史文本", r.getPresentIllness());
        assertEquals("主诉文本", r.getChiefComplaint());
        assertEquals("自诉文本", r.getSelfReport());
        assertEquals("望诊文本", r.getInspection());
        assertEquals("脉象文本", r.getPulse());
        assertEquals("舌象文本", r.getTongue());
        assertEquals("查体文本", r.getPhysicalExam());
        assertEquals("肝郁气滞", r.getPattern());
        assertEquals("柴胡疏肝散", r.getPrescription());
        assertEquals("随访文本", r.getFollowUp());
        assertEquals("显效", r.getTreatmentEffect());
        assertEquals("中医内科", r.getDepartment());
        assertEquals("D-1", r.getDoctorId());
        // 导入与单条新增同口径：新入库一律 pending（还没跑质控）
        assertEquals("pending", r.getStatus());
        assertEquals("org-A", r.getOrgId(), "必须带组织标记，否则各组织之间会互相看到病历");
    }

    /**
     * 「接诊时间」：真实来源是 14 位紧凑数字串（数值、General）。
     * 这条断言就是「病历侧能不能换流式读」的判定标准 —— 用 DataFormatter 的格式化文本
     * 会得到科学计数法，解析必失败、整列变 null。
     */
    @Test
    void parsesCompactNumericVisitTime() throws Exception {
        String[] data = {"REG-2", "OP-2", "女", "38", "1", "感冒", "风热犯表",
                "x", "y", "z", "a", "b", "c", "d", "风热犯表", "银翘散", "e", "治愈", "中医内科", "D-2",
                "20221224090613"};

        Record r = importAndCapture(xlsx(new String[][]{HEADER, data})).get(0);

        assertEquals(LocalDateTime.of(2022, 12, 24, 9, 6, 13), r.getVisitTime(),
                "紧凑数字日期时间必须解析出来，否则列表接诊时间整列空白");
    }

    /**
     * 25.2：单次导入的行数上限。
     *
     * 上限必须是跨文件累计的 —— 只按文件卡的话，20 个文件就把上限放大 20 倍。
     * 这里锁三件事：未到上限放行、恰好到上限拦下并留下文件级失败、拦下后不再产出待入库病历。
     * 第 3 条尤其重要：如果只拦「入库」却仍把行解析出来，调用方会拿到一份
     * 「total 很大但一行没入库」的自相矛盾摘要。
     */
    @Test
    void stopsImportWhenRowLimitReached() throws Exception {
        // 1. 未到上限：不拦，也不留失败明细
        ImportSummaryVO below = new ImportSummaryVO();
        below.setTotal(ExcelSheetImporter.MAX_ROWS - 1);
        assertFalse(ExcelSheetImporter.rowLimitReached(below, "records.xlsx"));
        assertEquals(0, below.getFailed());

        // 2. 恰好到上限（total 只计入带登记号的行）：拦下 + 一条文件级失败，文案要说清下一步
        ImportSummaryVO at = new ImportSummaryVO();
        at.setTotal(ExcelSheetImporter.MAX_ROWS);
        assertTrue(ExcelSheetImporter.rowLimitReached(at, "records.xlsx"));
        assertEquals(1, at.getFailed());
        String reason = at.getFailures().get(0).getReason();
        assertTrue(reason.contains("上限") && reason.contains("拆分"), reason);

        // 3. 已到上限：后续文件连读表都不做
        String[] data = {"REG-9", "OP-9", "男", "45", "2", "x", "y", "z", "a", "b", "c", "d", "e", "f",
                "g", "h", "i", "j", "中医内科", "D-9", "20221224090613"};
        List<Object[]> parsed = new ArrayList<>();
        ImportSummaryVO summary = new ImportSummaryVO();
        summary.setTotal(ExcelSheetImporter.MAX_ROWS);
        ExcelSheetImporter.importFile(xlsx(new String[][]{HEADER, data}), summary, parsed,
                new HashSet<>(), new int[]{0}, new ArrayList<>());
        assertTrue(parsed.isEmpty(), "到上限后不应再产出待入库病历");
        assertEquals(1, summary.getFailed(), "每个被拦下的文件记一条文件级失败");
    }
}
