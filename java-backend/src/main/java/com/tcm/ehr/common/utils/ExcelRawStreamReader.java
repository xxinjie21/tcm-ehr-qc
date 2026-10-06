package com.tcm.ehr.common.utils;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Excel 流式读的<b>第二档</b>：保留单元格的「类型 + 原始值」，而不是只给格式化文本。
 *
 * <p><b>为什么必须有这一档</b>：{@code DataFormatter} 会把大数写成科学计数法。实测证据：
 * 病历导入「接诊时间」列的真实来源是 14 位紧凑数字串（{@code 20221224090613}，类型数值、
 * 格式 General），格式化后变成 {@code 2.02212E+13}，过不了 {@code normalizeDateTime}，
 * 接诊时间会<b>整列静默变 null</b>（列表接诊时间列 / 就诊月份趋势 / 日期范围筛选 / 去重哈希
 * 一起退化 —— 2026-09-28 的事故形态）。</p>
 *
 * <p>做法：自己走 sheet XML 的事件流（不再经过 {@code XSSFSheetXMLHandler}）——
 * 从 {@code <c>} 取 {@code r}（列引用）与 {@code s}（样式索引），从 {@code <v>} 取<b>原始值</b>，
 * 共享字符串按索引回表解析；样式索引回表判断是否日期格式。于是每个单元格同时给出
 * 「原始值」与「能不能当日期用」，调用方按列语义自己决定怎么取，与既有 POI 全量读的
 * {@code cellText} / {@code parseDateTime} 口径一致。</p>
 */
public final class ExcelRawStreamReader {

    private ExcelRawStreamReader() {
    }

    /**
     * 一个单元格的原始信息。
     *
     * @param col           0 基列号
     * @param numeric       Excel 里是否为数值（含日期，日期在 Excel 里就是数值）
     * @param dateFormatted 是不是日期：数值 + 日期样式，或 OOXML 的 {@code t="d"} 日期型
     * @param number        数值型时的数值（非数值型为 0）
     * @param text          取值文本：数值型给「整数不带 .0」的十进制串（与 cellText 同口径），
     *                      字符串型给原文；{@code t="d"} 日期型给 ISO 原文
     * @param dateTime      日期型时解析好的本地日期时间，否则 null
     */
    public record RawCell(int col, boolean numeric, boolean dateFormatted, double number, String text,
                          LocalDateTime dateTime) {
    }

    /** 逐行回调：{@code rowNum} 为 <b>0 基</b>行号（与既有 POI 全量读的 {@code cellText} 同一口径） */
    public interface RowHandler {
        void row(int rowNum, List<RawCell> cells);
    }

    /** 只关心前 {@code cols} 列文本时的逐行回调：{@code cells} 定长，空位为 null */
    public interface TextRowHandler {
        void row(int rowNum, String[] cells);
    }

    /**
     * 流式读第一个工作表（只支持 .xlsx；.xls 无事件式 API）。
     *
     * <p><b>为什么要先落临时文件</b>：{@code OPCPackage.open(InputStream)} 走的是
     * {@code ZipInputStreamZipEntrySource}，其内部 {@code ZipArchiveFakeEntry} 会把 zip 条目
     * <b>整条读进内存</b> —— 既带来 POI 的「单记录 1 亿字节」上限（实测 20 万行即撞：
     * {@code RecordFormatException: Tried to read data but the maximum length for this record type
     * is 100,000,000}），又让 SAX 流式<b>根本没机会生效</b>（还没走到 sheet 解析就被拒）。
     * 换成 {@code OPCPackage.open(File)} 走 {@code FileZipEntrySource}，才是真正的边读边解析。</p>
     */
    public static void forEachXlsxRow(InputStream in, RowHandler handler) throws IOException {
        Path tmp = Files.createTempFile("xlsx-stream-", ".xlsx");
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try (OPCPackage pkg = OPCPackage.open(tmp.toFile())) {
                XSSFReader reader = new XSSFReader(pkg);
                StylesTable styles = reader.getStylesTable();
                ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);
                XMLReader parser = newXmlReader();
                parser.setContentHandler(new SheetHandler(styles, sst, handler));
                Iterator<InputStream> sheets = reader.getSheetsData();
                if (!sheets.hasNext()) {
                    return;
                }
                try (InputStream sheet = sheets.next()) {
                    parser.parse(new InputSource(sheet));
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Excel 读取失败：" + e.getMessage(), e);
            }
        } finally {
            // 临时文件必须删：上传侧已有 50MB 体积闸门，但仍不该在磁盘上留残留
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 便捷重载：只取前 {@code cols} 列的<b>文本</b>（词典导入这类纯文本表用）。
     *
     * <p>取值口径与既有 POI 全量读的 {@code cellText} 一致：字符串给原文并 trim、
     * 数值给「整数不带 .0」的十进制串、布尔 / 错误 / 空单元格给 null；缺的列补 null，
     * 返回数组长度恒为 {@code cols}。</p>
     *
     * <p>与已删除的格式化文本读取器的差别：这里不过 {@code DataFormatter}，
     * 大数不会变成科学计数法 —— 这正是把两档合并成本类的理由。</p>
     */
    public static void forEachXlsxRow(InputStream in, int cols, TextRowHandler handler) throws IOException {
        forEachXlsxRow(in, (rowNum, cells) -> {
            String[] out = new String[cols];
            for (RawCell c : cells) {
                int col = c.col();
                if (col < 0 || col >= cols || c.text() == null) {
                    continue;
                }
                String text = c.text().trim();
                out[col] = text.isEmpty() ? null : text;
            }
            handler.row(rowNum, out);
        });
    }

    /** 把数值按既有 cellText 的口径转成文本：整数不带 .0，小数保留 */
    static String numberToText(double d) {
        return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 数值 + 日期格式 → 本地日期时间（与 {@code Cell.getLocalDateTimeCellValue()} 同源） */
    public static LocalDateTime localDateTime(RawCell cell) {
        if (cell.dateTime() != null) {
            return cell.dateTime();
        }
        return DateUtil.getLocalDateTime(cell.number());
    }

    private static XMLReader newXmlReader() throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(false);
        try {
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // 该 JDK 实现不支持此特性：工作表 XML 不带 DOCTYPE，不阻断
        }
        return spf.newSAXParser().getXMLReader();
    }

    /** {@code <row>} → {@code <c r=".." s=".." t="..">} → {@code <v>原始值</v>} */
    private static final class SheetHandler extends DefaultHandler {
        private final StylesTable styles;
        private final ReadOnlySharedStringsTable sst;
        private final RowHandler target;

        private int rowNum = -1;
        private List<RawCell> cells = new ArrayList<>();
        private int col = -1;
        private int styleIdx = -1;
        private String type;
        private boolean inValue;
        private StringBuilder value;

        private SheetHandler(StylesTable styles, ReadOnlySharedStringsTable sst, RowHandler target) {
            this.styles = styles;
            this.sst = sst;
            this.target = target;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            switch (qName) {
                case "row" -> {
                    // Excel 的 r 属性是 1 基；对外统一成 0 基（调用方按「第 0 行是表头」处理）
                    rowNum = parseInt(attrs.getValue("r"), rowNum + 2) - 1;
                    cells = new ArrayList<>();
                }
                case "c" -> {
                    col = columnIndex(attrs.getValue("r"));
                    styleIdx = parseInt(attrs.getValue("s"), -1);
                    type = attrs.getValue("t");
                    value = null;
                }
                case "v", "t" -> {
                    // <v> 是普通单元格的值（共享字符串时是索引），<t> 是内联字符串的文本。
                    // ⚠️ 必须一起收：SXSSFWorkbook 这类流式写入器把字符串写成
                    // <c t="inlineStr"><is><t>文本</t></is></c>，只认 <v> 会把整份文件的
                    // 文本列读成空 —— 实测表现为「缺少表头」（表头读不到任何文本）。
                    inValue = true;
                    value = new StringBuilder();
                }
                default -> {
                    // 其它元素（f / is / t 等）不参与取值：<v> 已经给了我们要的原始值
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue && value != null) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("v".equals(qName) || "t".equals(qName)) {
                inValue = false;
                return;
            }
            if ("c".equals(qName)) {
                String raw = value == null ? null : value.toString().trim();
                cells.add(toCell(raw));
                col = -1;
                styleIdx = -1;
                type = null;
                value = null;
                return;
            }
            if ("row".equals(qName)) {
                // 行结束时交付；空行（没有任何单元格）也要回调，调用方自行决定是否忽略
                target.row(rowNum, cells);
                cells = new ArrayList<>();
            }
        }

        private RawCell toCell(String raw) {
            // 1. 字符串型（共享字符串 / 内联 / 公式串）
            if ("s".equals(type)) {
                int idx = parseInt(raw, -1);
                String text = idx >= 0 ? String.valueOf(sst.getItemAt(idx)) : null;
                return new RawCell(col, false, false, 0, text, null);
            }
            if ("inlineStr".equals(type) || "str".equals(type)) {
                return new RawCell(col, false, false, 0, raw, null);
            }
            // 2. 布尔 / 错误：既有 cellText 一律给 null（保持同口径）
            if ("b".equals(type) || "e".equals(type)) {
                return new RawCell(col, false, false, 0, null, null);
            }
            // 3. OOXML 的日期型（t="d"）：值是 ISO 字符串。POI 5.x 写 LocalDateTime 单元格
            //    就是这种形态 —— 若不单独认它，「真日期列」会被当成普通字符串，日期语义丢失
            if ("d".equals(type)) {
                if (raw == null || raw.isEmpty()) {
                    return new RawCell(col, false, false, 0, null, null);
                }
                LocalDateTime dt = null;
                try {
                    dt = LocalDateTime.parse(raw);
                } catch (Exception e) {
                    // 认不出就只给原文，交给调用方的文本解析分支
                }
                return new RawCell(col, false, true, 0, raw, dt);
            }
            // 4. 数值（含日期样式）：保留原始值，另给「是不是日期格式」
            if (raw == null || raw.isEmpty()) {
                return new RawCell(col, false, false, 0, null, null);
            }
            double d;
            try {
                d = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return new RawCell(col, false, false, 0, raw, null);
            }
            boolean dateFormatted = styleIdx >= 0 && styles != null && DateUtil.isADateFormat(
                    styles.getStyleAt(styleIdx).getDataFormat(),
                    styles.getStyleAt(styleIdx).getDataFormatString());
            LocalDateTime dt = dateFormatted ? DateUtil.getLocalDateTime(d) : null;
            return new RawCell(col, true, dateFormatted, d, numberToText(d), dt);
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "C7" → 2（0 基列号）；取不出字母时返回 -1 */
    static int columnIndex(String cellReference) {
        if (cellReference == null) {
            return -1;
        }
        int col = 0;
        int i = 0;
        while (i < cellReference.length()) {
            char c = cellReference.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                col = col * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                col = col * 26 + (c - 'a' + 1);
            } else {
                break;
            }
            i++;
        }
        return i == 0 ? -1 : col - 1;
    }
}
