package com.tcm.ehr.common.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 15：保留「类型 + 原始值」的流式读（治 {@code DataFormatter} 科学计数法那个坑）。
 *
 * <p>这些断言就是接线病历导入的前提条件：紧凑数字日期时间列必须原样给数字串，
 * 真日期列必须能被识别成日期。缺一条，导入就会静默丢接诊时间。</p>
 */
class ExcelRawStreamReaderTest {

    private static List<List<ExcelRawStreamReader.RawCell>> read(byte[] bytes) throws Exception {
        List<List<ExcelRawStreamReader.RawCell>> rows = new ArrayList<>();
        ExcelRawStreamReader.forEachXlsxRow(new ByteArrayInputStream(bytes),
                (rowNum, cells) -> rows.add(cells));
        return rows;
    }

    private static byte[] build(boolean withDate) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("s");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("登记号");
            header.createCell(1).setCellValue("接诊时间");
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("A-1");
            // 真实来源：14 位紧凑数字串，数值类型 + General 格式
            r.createCell(1).setCellValue(20221224090613L);
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("A-2");
            if (withDate) {
                // Excel 真日期单元格 = 数值 + 日期样式。
                // ⚠️ POI 的 setCellValue(LocalDateTime) 不会自动套日期样式（存成普通数值），
                //    必须显式建一个日期格式的 CellStyle，否则读回来只是「一个数」
                org.apache.poi.ss.usermodel.CellStyle dateStyle = wb.createCellStyle();
                dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
                org.apache.poi.ss.usermodel.Cell dc = r2.createCell(1);
                dc.setCellValue(LocalDateTime.of(2024, 1, 5, 9, 30));
                dc.setCellStyle(dateStyle);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static ExcelRawStreamReader.RawCell cell(List<ExcelRawStreamReader.RawCell> row, int col) {
        return row.stream().filter(c -> c.col() == col).findFirst().orElse(null);
    }

    /** 治坑的那一条：紧凑数字串必须原样，不能变成科学计数法 */
    @Test
    void compactNumericDateTimeStaysPlainDigits() throws Exception {
        List<List<ExcelRawStreamReader.RawCell>> rows = read(build(false));
        ExcelRawStreamReader.RawCell c = cell(rows.get(1), 1);
        assertEquals("20221224090613", c.text(),
                "必须与既有 cellText 同形（纯数字），否则接诊时间整列变 null");
        assertTrue(c.numeric());
        assertFalse(c.dateFormatted(), "General 格式不是日期格式，应走文本解析分支");
    }

    /** 真日期单元格：必须被认成日期，且能取回与写入时相同的时刻 */
    @Test
    void realDateCellIsRecognizedAsDate() throws Exception {
        List<List<ExcelRawStreamReader.RawCell>> rows = read(build(true));
        ExcelRawStreamReader.RawCell c = cell(rows.get(2), 1);
        assertTrue(c.dateFormatted(), "日期格式的数值单元格必须被识别，否则会退化成文本解析");
        assertEquals(LocalDateTime.of(2024, 1, 5, 9, 30), ExcelRawStreamReader.localDateTime(c));
    }

    /** 字符串列：共享字符串要回表解析（表头与登记号） */
    @Test
    void sharedStringsAreResolved() throws Exception {
        List<List<ExcelRawStreamReader.RawCell>> rows = read(build(false));
        assertEquals("登记号", cell(rows.get(0), 0).text());
        assertEquals("A-1", cell(rows.get(1), 0).text());
        assertFalse(cell(rows.get(1), 0).numeric());
    }

    /** 缺列：不存在的单元格不会被造出来，调用方须按列号自己兜 null */
    @Test
    void absentCellIsNotEmitted() throws Exception {
        List<List<ExcelRawStreamReader.RawCell>> rows = read(build(false));
        assertNull(cell(rows.get(2), 1), "第 3 行没有第二列：不应凭空造一个空单元格");
        assertEquals(3, rows.size(), "表头 + 两行数据都应回调");
    }

    /** 数值文本口径与既有 cellText 一致：整数不带 .0，小数保留 */
    @Test
    void numberToTextMatchesCellText() {
        assertEquals("301", ExcelRawStreamReader.numberToText(301.0));
        assertEquals("3.01", ExcelRawStreamReader.numberToText(3.01));
        assertEquals("20221224090613", ExcelRawStreamReader.numberToText(20221224090613d));
    }

    /**
     * 行号契约：对外必须是 0 基（调用方普遍按「第 0 行是表头」处理）。
     *
     * <p>补这条的原因很直白：此前只断言了「回调按顺序进列表」，从没断言过 {@code rowNum}
     * 的值 —— 于是 1 基的行号一路活到接线时，表现为「表头被当成数据行、整份文件 0 行 0 失败」。</p>
     */
    /**
     * 内联字符串：SXSSFWorkbook 等流式写入器把字符串写成 {@code <is><t>}。
     * 这条是压测里实测出来的 —— 只认 {@code <v>} 时整份文件的文本列读成空，
     * 表现为「缺少表头」。
     */
    @Test
    void inlineStringsAreResolved() throws Exception {
        byte[] bytes;
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook wb =
                     new org.apache.poi.xssf.streaming.SXSSFWorkbook(10);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("s");
            org.apache.poi.ss.usermodel.Row r = sheet.createRow(0);
            r.createCell(0).setCellValue("登记号");
            r.createCell(1).setCellValue("接诊时间");
            wb.write(out);
            wb.dispose();
            bytes = out.toByteArray();
        }
        List<ExcelRawStreamReader.RawCell> header = read(bytes).get(0);
        assertEquals("登记号", cell(header, 0).text(), "内联字符串必须解析出来");
        assertEquals("接诊时间", cell(header, 1).text());
    }

    @Test
    void rowNumbersAreZeroBased() throws Exception {
        List<Integer> seen = new ArrayList<>();
        ExcelRawStreamReader.forEachXlsxRow(new ByteArrayInputStream(build(false)),
                (rowNum, cells) -> seen.add(rowNum));
        assertEquals(List.of(0, 1, 2), seen, "行号必须从 0 起，否则表头会被当成数据行");
    }

    @Test
    void columnIndexParsesReferences() {
        assertEquals(0, ExcelRawStreamReader.columnIndex("A1"));
        assertEquals(2, ExcelRawStreamReader.columnIndex("C7"));
        assertEquals(26, ExcelRawStreamReader.columnIndex("AA1"));
        assertEquals(-1, ExcelRawStreamReader.columnIndex("1"));
    }

    // ---- 文本重载（原 ExcelStreamReader 的职责，2026-10 合并进本类） ----

    /** 造一份 .xlsx：表头 + 中文词条 + 空别名 + 整数代码 + 小数代码 + 全空行 */
    private static byte[] sampleXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("词典");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("标准术语");
            header.createCell(1).setCellValue("别名");
            header.createCell(2).setCellValue("第三列");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("  肝郁气滞  "); // 带空白：应被 trim
            r1.createCell(1).setCellValue("肝气郁结、肝郁");
            r1.createCell(2).setCellValue(301);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("脾虚湿困");
            // 别名列留空（不写单元格）：应为 null
            r2.createCell(2).setCellValue(3.01);

            sheet.createRow(3); // 三列全空的行：调用方按全 null 丢弃

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 文本重载的逐行回调收成列表（与 DictionaryServiceImpl 一样的用法） */
    private static List<String[]> viaText(byte[] bytes) throws Exception {
        List<String[]> rows = new ArrayList<>();
        ExcelRawStreamReader.forEachXlsxRow(new ByteArrayInputStream(bytes), 3, (rowNum, cells) -> {
            if (rowNum == 0) {
                return; // 表头跳过
            }
            if (cells[0] != null || cells[1] != null || cells[2] != null) {
                rows.add(cells);
            }
        });
        return rows;
    }

    /** 既有 POI 全量读的口径（DictionaryServiceImpl.cellText 的语义复刻） */
    private static List<String[]> viaPoi(byte[] bytes) throws Exception {
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                if (r.getRowNum() == 0) {
                    continue;
                }
                String[] arr = new String[3];
                for (int i = 0; i < 3; i++) {
                    arr[i] = cellText(r.getCell(i));
                }
                if (arr[0] != null || arr[1] != null || arr[2] != null) {
                    rows.add(arr);
                }
            }
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case FORMULA -> cell.toString().trim();
            default -> null;
        };
    }

    /**
     * 文本重载与既有 POI 全量读<b>逐行逐列等价</b>：口径漂了只会「导进去的词条莫名多/少」，
     * 不会报错 —— 只能靠比对钉住。
     */
    @Test
    void textOverloadMatchesPoiRows() throws Exception {
        byte[] bytes = sampleXlsx();
        List<String[]> text = viaText(bytes);
        List<String[]> poi = viaPoi(bytes);
        assertEquals(poi.size(), text.size(), () -> "行数不同：POI=" + poi.size() + " SAX=" + text.size());
        for (int i = 0; i < poi.size(); i++) {
            for (int c = 0; c < 3; c++) {
                final int ri = i;
                final int ci = c;
                assertEquals(poi.get(i)[c], text.get(i)[c], () -> "第 " + ri + " 行第 " + ci + " 列不同");
            }
        }
    }

    @Test
    void textOverloadTrimsAndKeepsNumberShape() throws Exception {
        List<String[]> rows = viaText(sampleXlsx());
        assertEquals(2, rows.size(), "三列全空的行必须被丢弃");
        assertEquals("肝郁气滞", rows.get(0)[0], "字符串必须 trim");
        assertEquals("肝气郁结、肝郁", rows.get(0)[1]);
        assertEquals("301", rows.get(0)[2], "整数不能带 .0");
        assertEquals("脾虚湿困", rows.get(1)[0]);
        assertNull(rows.get(1)[1], "空单元格必须是 null 而不是空串");
        assertEquals("3.01", rows.get(1)[2], "小数要原样保留");
    }
}
