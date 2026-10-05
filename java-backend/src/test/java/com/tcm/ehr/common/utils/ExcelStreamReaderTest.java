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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 15：SAX 流式读与既有 POI 全量读的<b>等价性</b>。
 *
 * <p>为什么必须有这条测试：解析路径承载着词典导入的取值口径（字符串 trim、整数不带 {@code .0}、
 * 空单元格给 null、三列全空的行丢弃、首行表头跳过）。改成流式读时若口径漂了，
 * 表现是「导进去的词条莫名其妙多了/少了」，不会报错 —— 只能靠逐行比对钉住。</p>
 *
 * <p><b>已知且有意接受的差异</b>：SAX 的 {@code SheetContentsHandler} 只给「格式化文本」、
 * <b>不给单元格类型</b>，故 BOOLEAN 单元格会回成 {@code "TRUE"} 而不是既有 {@code cellText}
 * 的 null。词典导入只关心标准术语 / 别名 / 国标代码三列，布尔不可能出现在这三列，
 * 且 .xls 路径仍走 POI 全量读 —— 这里显式记下来，避免后人以为漏了。</p>
 */
class ExcelStreamReaderTest {

    /** 造一份 .xlsx：表头 + 中文词条 + 空别名 + 整数代码 + 小数代码 + 全空行 */
    private static byte[] sampleXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("词典");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("标准术语");
            header.createCell(1).setCellValue("别名");
            header.createCell(2).setCellValue("国标代码");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("  肝郁气滞  "); // 带空白：应被 trim
            r1.createCell(1).setCellValue("肝气郁结、肝郁");
            r1.createCell(2).setCellValue(301);

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("脾虚湿困");
            // 别名列留空（不写单元格）：应为 null
            r2.createCell(2).setCellValue(3.01);

            sheet.createRow(3); // 三列全空的行：应被丢弃

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 逐行回调收成列表（与调用方一样的用法） */
    private static List<String[]> viaSax(byte[] bytes) throws Exception {
        List<String[]> rows = new ArrayList<>();
        ExcelStreamReader.forEachXlsxRow(new ByteArrayInputStream(bytes), 3, (rowNum, cells) -> {
            if (rowNum == 0) {
                return; // 表头跳过（与 DictionaryServiceImpl 同口径）
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
        CellType type = cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                yield d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            case FORMULA -> cell.toString().trim();
            default -> null;
        };
    }

    @Test
    void saxAndPoiProduceSameRows() throws Exception {
        byte[] bytes = sampleXlsx();
        List<String[]> sax = viaSax(bytes);
        List<String[]> poi = viaPoi(bytes);

        assertEquals(poi.size(), sax.size(), () -> "行数不同：POI=" + poi.size() + " SAX=" + sax.size());
        for (int i = 0; i < poi.size(); i++) {
            for (int c = 0; c < 3; c++) {
                final int ri = i;
                final int ci = c;
                assertEquals(poi.get(i)[c], sax.get(i)[c],
                        () -> "第 " + ri + " 行第 " + ci + " 列不同");
            }
        }
    }

    @Test
    void saxTrimsTextAndKeepsNumberShape() throws Exception {
        List<String[]> rows = viaSax(sampleXlsx());
        assertEquals(2, rows.size(), "三列全空的行必须被丢弃");
        assertEquals("肝郁气滞", rows.get(0)[0], "字符串必须 trim");
        assertEquals("肝气郁结、肝郁", rows.get(0)[1]);
        assertEquals("301", rows.get(0)[2], "整数不能带 .0");
        assertEquals("脾虚湿困", rows.get(1)[0]);
        assertNull(rows.get(1)[1], "空单元格必须是 null 而不是空串");
        assertEquals("3.01", rows.get(1)[2], "小数要原样保留");
    }

    @Test
    void columnIndexParsesReferences() {
        assertEquals(0, ExcelStreamReader.columnIndex("A1"));
        assertEquals(2, ExcelStreamReader.columnIndex("C7"));
        assertEquals(26, ExcelStreamReader.columnIndex("AA1"));
        assertTrue(ExcelStreamReader.columnIndex("1") < 0, "取不出列字母时返回 -1");
    }
}
