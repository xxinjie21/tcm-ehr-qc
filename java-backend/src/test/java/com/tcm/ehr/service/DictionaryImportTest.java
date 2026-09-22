package com.tcm.ehr.service;

import com.tcm.ehr.common.config.LlmConfigStore;
import com.tcm.ehr.common.config.LlmProperties;
import com.tcm.ehr.common.utils.DictionaryStore;
import com.tcm.ehr.common.utils.LlmClient;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.impl.DictionaryServiceImpl;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 词典导入链路测试（批A·1.4）：JSON 直传 / Excel(.xlsx,.xls) / CSV 三列（含国标代码），
 * 以及 PDF 智能转换的开关兜底文案。
 *
 * <p>用真实 POI 生成 .xls/.xlsx 字节流（不依赖外部文件），
 * 文件服务与 ES 服务用 Mockito 替身，<b>不碰真实词库文件与 ES</b>。</p>
 */
class DictionaryImportTest {

    private static final String TYPE = "symptom";

    private IDictionaryFileService fileService;
    private DictionaryStore store;
    private IEsTermIndexService esIndex;
    private DictionaryServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        fileService = Mockito.mock(IDictionaryFileService.class);
        store = new DictionaryStore();
        esIndex = Mockito.mock(IEsTermIndexService.class);
        when(fileService.read(anyString())).thenReturn(List.of());
        when(fileService.backup(anyString())).thenReturn("symptoms.json.bak_test");

        service = newService(false, false);
    }

    /** 按开关状态构造服务（避免反射改 final 字段） */
    private DictionaryServiceImpl newService(boolean llmEnabled, boolean convertEnabled) {
        LlmProperties props = new LlmProperties();
        props.setEnabled(llmEnabled);
        LlmClient client = new LlmClient(new LlmConfigStore(props));
        DictionaryServiceImpl s = new DictionaryServiceImpl(fileService, store, esIndex,
                new ObjectMapper(), client);
        ReflectionTestUtils.setField(s, "convertEnabled", convertEnabled);
        return s;
    }

    private static MockMultipartFile json(String name, String body) {
        return new MockMultipartFile("file", name, "application/json",
                body.getBytes(StandardCharsets.UTF_8));
    }

    /** 用 POI 生成两列/三列表格；xls=true 走 HSSF(.xls)，否则 XSSF(.xlsx) */
    private static byte[] workbook(boolean xls, boolean withCodeColumn) throws IOException {
        try (Workbook wb = xls ? new HSSFWorkbook() : new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("术语");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("标准术语");
            header.createCell(1).setCellValue("别名");
            if (withCodeColumn) header.createCell(2).setCellValue("国标代码");

            String[][] rows = {
                    {"喉痹", "咽喉痛、咽痛", "A08.01"},
                    {"鼻鼽", "过敏性鼻炎", "A09.02"},
                    {"", "孤儿别名", "X00"},          // 标准术语为空 -> 应进 failures
            };
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue(rows[i][0]);
                r.createCell(1).setCellValue(rows[i][1]);
                if (withCodeColumn) r.createCell(2).setCellValue(rows[i][2]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private List<TermEntry> capturedWritten() throws IOException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TermEntry>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(fileService).write(Mockito.eq(TYPE), captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- JSON 直传

    /** JSON 直传：解析 TermEntry 数组，别名去重、code 保留 */
    @Test
    void jsonImport_shouldParseAliasesAndCode() throws IOException {
        String body = """
                [
                  {"standardTerm":"喉痹","aliases":["咽喉痛","咽痛"],"source":"中医临床诊疗术语 症状","code":"A08.01"},
                  {"standardTerm":"喉痹","aliases":["咽痛"],"source":"","code":null}
                ]""";

        ImportResultVO vo = service.importDictionary(TYPE, json("d.json", body));

        assertEquals(2, vo.getTotal());
        assertEquals(2, vo.getImported());
        assertEquals(0, vo.getFailed());

        // 两条同 standardTerm 合并为 1 条，别名去重
        List<TermEntry> written = capturedWritten();
        assertEquals(1, written.size());
        TermEntry merged = written.get(0);
        assertEquals("喉痹", merged.getStandardTerm());
        assertEquals(List.of("咽喉痛", "咽痛"), merged.getAliases());
        assertEquals("A08.01", merged.getCode());
    }

    /** JSON 里 standardTerm 为空：记入失败明细而不是整批报错 */
    @Test
    void jsonImport_blankStandardTerm_shouldRecordFailure() throws IOException {
        String body = "[{\"standardTerm\":\"  \",\"aliases\":[\"x\"]},{\"standardTerm\":\"喉痹\"}]";

        ImportResultVO vo = service.importDictionary(TYPE, json("d.json", body));

        assertEquals(2, vo.getTotal());
        assertEquals(1, vo.getImported());
        assertEquals(1, vo.getFailed());
        // UX-55：失败原因面向使用者，不得出现 standardTerm 这类字段名
        assertEquals("标准术语列为空", vo.getFailures().get(0).get("reason"));
    }

    /** 非法 JSON：抛 IllegalArgumentException，由全局异常处理回 400 */
    @Test
    void jsonImport_invalidJson_shouldThrow() {
        MultipartFile bad = json("d.json", "{不是数组}");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.importDictionary(TYPE, bad));
        assertTrue(e.getMessage().contains("JSON 解析失败"), e.getMessage());
    }

    // ---------------------------------------------------------------- Excel / CSV

    /** .xls（HSSF）三列：标准术语/别名/国标代码 —— 复验老格式仍可用 */
    @Test
    void xlsImport_shouldReadThreeColumns() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "terms.xls",
                "application/vnd.ms-excel", workbook(true, true));

        ImportResultVO vo = service.importDictionary(TYPE, file);

        assertEquals(3, vo.getTotal());
        assertEquals(2, vo.getImported());
        assertEquals(1, vo.getFailed());

        List<TermEntry> written = capturedWritten();
        assertEquals(2, written.size());
        assertEquals("喉痹", written.get(0).getStandardTerm());
        assertEquals(List.of("咽喉痛", "咽痛"), written.get(0).getAliases());
        assertEquals("A08.01", written.get(0).getCode());
        assertEquals("A09.02", written.get(1).getCode());
    }

    /** .xlsx（XSSF）三列 */
    @Test
    void xlsxImport_shouldReadThreeColumns() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "terms.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbook(false, true));

        ImportResultVO vo = service.importDictionary(TYPE, file);

        assertEquals(2, vo.getImported());
        assertEquals("A08.01", capturedWritten().get(0).getCode());
    }

    /** 无第 3 列时 code 应为 null（不报错），保持旧模板兼容 */
    @Test
    void xlsxImport_withoutCodeColumn_shouldLeaveCodeNull() throws IOException {
        MultipartFile file = new MockMultipartFile("file", "terms.xlsx",
                "application/octet-stream", workbook(false, false));

        ImportResultVO vo = service.importDictionary(TYPE, file);

        assertEquals(2, vo.getImported());
        assertNull(capturedWritten().get(0).getCode());
    }

    /** CSV 三列：制表符/逗号分隔，第 3 列进 code */
    @Test
    void csvImport_shouldReadThreeColumns() throws IOException {
        String csv = "标准术语,别名,国标代码\n喉痹,咽喉痛、咽痛,A08.01\n鼻鼽,过敏性鼻炎,A09.02\n";
        MultipartFile file = new MockMultipartFile("file", "terms.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        ImportResultVO vo = service.importDictionary(TYPE, file);

        assertEquals(2, vo.getImported());
        List<TermEntry> written = capturedWritten();
        assertEquals("喉痹", written.get(0).getStandardTerm());
        assertEquals("A08.01", written.get(0).getCode());
    }

    /** 不支持的扩展名：明确报错，不静默吞掉 */
    @Test
    void unsupportedFormat_shouldThrow() {
        MultipartFile file = new MockMultipartFile("file", "terms.txt", "text/plain",
                "abc".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.importDictionary(TYPE, file));
        assertTrue(e.getMessage().contains("文件格式不支持"), e.getMessage());
    }

    /** 导入确实走完「备份 -> 写文件 -> 刷内存 -> 重建ES」四步 */
    @Test
    void import_shouldBackupWriteRefreshAndRebuild() throws IOException {
        service.importDictionary(TYPE, json("d.json", "[{\"standardTerm\":\"喉痹\"}]"));

        Mockito.verify(fileService).backup(TYPE);
        Mockito.verify(fileService).write(Mockito.eq(TYPE), any());
        Mockito.verify(esIndex).rebuild(Mockito.eq(TYPE), any());
        assertEquals(1, store.size(TYPE));
    }

    // ---------------------------------------------------------------- PDF 转换兜底

    /** 开关关闭（llm.enabled 与 llm.convert-enabled 均 false）：回友好文案而非 500 */
    @Test
    void convertWhenDisabled_shouldGiveFriendlyMessage() {
        MultipartFile pdf = new MockMultipartFile("file", "gb.pdf", "application/pdf",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.convertFromPdf(TYPE, pdf));

        assertTrue(e.getMessage().contains("PDF 智能转换未启用"), e.getMessage());
        assertTrue(e.getMessage().contains("JSON 直传"), "文案应给出替代路径：" + e.getMessage());
        assertTrue(e.getMessage().contains("convert-standard-pdf.py"), "文案应指向离线脚本");
    }

    /** 非 PDF 文件走 convert：明确拒绝，不误当作 PDF 解析 */
    @Test
    void convertWithNonPdf_shouldThrow() {
        DictionaryServiceImpl enabled = newService(true, true);
        MultipartFile xlsx = new MockMultipartFile("file", "terms.xlsx",
                "application/octet-stream", new byte[]{1, 2, 3});

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> enabled.convertFromPdf(TYPE, xlsx));
        assertTrue(e.getMessage().contains("仅支持 .pdf"), e.getMessage());
    }
}
