package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.ExcelRawStreamReader;
import com.tcm.ehr.common.utils.TextUtil;
import org.apache.poi.ss.usermodel.Row;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「一行怎么取值」的抽象（批次 13 · 13.3 的第四个抽取对象）。
 *
 * POI 全量读与 SAX 流式读共用同一套 21 列映射。抽它的理由（原样搬来）：
 * 两条路径的<b>取值口径</b>必须一致（字符串 trim、数值整数不带 .0、日期按类型或文本解析）。
 * 若各写一份映射，迟早出现「xlsx 导入少一列、xls 正常」这类只在某种格式下复现的问题。
 */
final class ExcelRowReader {

    private ExcelRowReader() {
    }

    /** 一行怎么取值：按列号取文本 / 取接诊时间 */
    interface RowAccess {
        /** 按列号取文本（与 {@link ExcelCellParser#cellText} 同口径） */
        String text(int col);

        /** 按列号取接诊时间（与 {@link ExcelCellParser#parseDateTime} 同口径） */
        LocalDateTime dateTime(int col);
    }

    /** POI 行适配器 */
    static RowAccess of(Row row) {
        return new RowAccess() {
            @Override
            public String text(int col) {
                return ExcelCellParser.cellText(row.getCell(col));
            }

            @Override
            public LocalDateTime dateTime(int col) {
                return ExcelCellParser.parseDateTime(row.getCell(col));
            }
        };
    }

    /** 流式行适配器：RawCell 同时带原始值与「是不是日期」，日期列因此不会退化成文本 */
    static RowAccess of(List<ExcelRawStreamReader.RawCell> cells) {
        // P2-6：列号 → 单元格 一次遍历建成。原先每次取值都线性扫一遍 cells，
        // 一行 21 列、每列最多取两次（text + dateTime）≈ 二十多次线性扫描/行 ——
        // 4 万行就是近百万次扫描。列号可任意（空列会被跳过），故按 Map 存而不是按数组下标。
        // putIfAbsent 保持「同列号取先出现的那一个」，与原先 find 的语义一致。
        Map<Integer, ExcelRawStreamReader.RawCell> byCol = new HashMap<>(cells.size() * 2);
        for (ExcelRawStreamReader.RawCell c : cells) {
            byCol.putIfAbsent(c.col(), c);
        }
        return new RowAccess() {
            @Override
            public String text(int col) {
                ExcelRawStreamReader.RawCell c = byCol.get(col);
                return c == null ? null : c.text();
            }

            @Override
            public LocalDateTime dateTime(int col) {
                ExcelRawStreamReader.RawCell c = byCol.get(col);
                if (c == null) {
                    return null;
                }
                // 与 parseDateTime 同口径：真日期型直接取值；其余按文本归一后解析
                if (c.dateFormatted()) {
                    return ExcelRawStreamReader.localDateTime(c);
                }
                String s = c.text();
                if (TextUtil.isBlank(s)) {
                    return null;
                }
                try {
                    return LocalDateTime.parse(ExcelCellParser.normalizeDateTime(s), ExcelCellParser.formatter());
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }
}
