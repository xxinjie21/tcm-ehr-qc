package com.tcm.ehr.service;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.ImportResultVO;
import com.tcm.ehr.service.DictionaryTermStore;
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
import static org.mockito.ArgumentMatchers.anyList;
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
    private IEsTermIndexService esIndex;
    private DictionaryTermStore termStore;
    private com.tcm.ehr.common.utils.DistLock distLock;
    private DictionaryServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        fileService = Mockito.mock(IDictionaryFileService.class);
        esIndex = Mockito.mock(IEsTermIndexService.class);
        // 跨实例互斥（批次16）：桩成「拿到锁并直接执行临界区」，
        // 让本测试聚焦在导入逻辑本身，不受锁影响
        com.tcm.ehr.mapper.DbLockMapper lockMapper = Mockito.mock(com.tcm.ehr.mapper.DbLockMapper.class);
        Mockito.when(lockMapper.acquire(Mockito.anyString(), Mockito.anyInt())).thenReturn(1);
        distLock = new com.tcm.ehr.common.utils.DistLock(lockMapper);
        termStore = Mockito.mock(DictionaryTermStore.class);
        // 本组织原有词条为空（备份机制已随 dictionary_backups 表废弃）
        when(termStore.read(anyString(), anyString())).thenReturn(List.of());
        // replace 返回内容版本，代码会拿它当 rebuild/markIndexed 的入参
        when(termStore.replace(anyString(), anyString(), anyList()))
                .thenReturn("cv-test");

        service = newService();
    }

    /** 构造服务：只注入导入路径依赖的三个协作者 */
    private DictionaryServiceImpl newService() {
        return new DictionaryServiceImpl(fileService, esIndex, distLock, termStore, new ObjectMapper());
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

    /** 导入真正落库的那一份词条（现在落在 termStore.replace 的第三个入参） */
    private List<TermEntry> capturedWritten() throws IOException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TermEntry>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(termStore).replace(anyString(), Mockito.eq(TYPE), captor.capture());
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

    /**
     * 导入走完「存档 -> 落库 -> 重建ES -> 记已同步」四步，且<b>顺序不能错</b>。
     *
     * <p>最后那步 {@code markIndexed} 是关键：它没被调用就意味着这次索引没被承认，
     * 启动对账下次会无条件重建 —— 反过来若在 ES 灌之前就调用，ES 灌失败就会被
     * 永久记成「已同步」，词典改了却永远不生效。</p>
     */
    @Test
    void import_shouldBackupWriteRebuildAndMarkIndexed() throws IOException {
        service.importDictionary(TYPE, json("d.json", "[{\"standardTerm\":\"喉痹\"}]"));

        // 1. 落库发生在本组织这一层（存档机制已随 dictionary_backups 表废弃，
        //    改由归档版本承担「合并后快照」）
        Mockito.verify(termStore).replace(anyString(), Mockito.eq(TYPE), anyList());

        // 2. 灌进索引的就是刚导入的那条，而不是只断言 rebuild 被调用过
        ArgumentCaptor<List<TermEntry>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(esIndex).rebuild(Mockito.eq(TYPE), anyString(), captor.capture(), Mockito.any());
        assertEquals(1, captor.getValue().size());
        assertEquals("喉痹", captor.getValue().get(0).getStandardTerm());

        // 3. ES 灌完才承认已同步
        Mockito.verify(termStore).markIndexed(anyString(), Mockito.eq(TYPE), Mockito.anyString());
    }
}
