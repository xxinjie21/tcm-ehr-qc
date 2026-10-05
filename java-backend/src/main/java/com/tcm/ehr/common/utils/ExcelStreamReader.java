package com.tcm.ehr.common.utils;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Excel 的<b>流式</b>逐行读取：`.xlsx` 走 SAX，不再把整份工作簿载入内存。
 *
 * <p>为什么需要它：原先两侧导入都用 {@code WorkbookFactory.create(in)}，那是<b>全量载入</b>——
 * 50MB 的 xlsx 解压后可达数百 MB，能把堆撑爆并拖垮整个进程（不只是这一个请求失败）。
 * 体积闸门（50MB）只是把阈值推后，没有改变「内存 ≈ 解压后体积」这个事实。</p>
 *
 * <p><b>为什么只支持 .xlsx</b>：SAX 是 OOXML 才有的能力。`.xls`（HSSF）的解析器本身就要把
 * 整个 BIFF 流读进内存，没有事件式 API —— 调用方对 `.xls` 继续走 POI 全量载入 + 体积闸门，
 * 这是有意的取舍，不是漏改。</p>
 *
 * <p>取的是 {@link org.apache.poi.ss.usermodel.DataFormatter} 的<b>格式化文本</b>
 * （与页面上看到的显示值一致），故日期/数字列不会拿到 Excel 内部的数值序列。</p>
 *
 * <p><b>⚠️ 适用范围（2026-10-05 实测出来的硬约束，接病历导入前必读）</b>：
 * 本类只给「格式化文本」，<b>不能</b>用于需要区分单元格类型的列。实测证据 ——
 * 病历导入「接诊时间」列的真实来源是 <b>14 位紧凑数字串</b>（{@code 20221224090613}），
 * 单元格类型为数值、格式为 General：既有 {@code cellText} 给它 {@code String.valueOf((long) d)}
 * （即 {@code 20221224090613}），而本类经 {@code DataFormatter} 给的是
 * {@code 2.02212E+13}（科学计数法）—— 后者过不了 {@code normalizeDateTime}，
 * 接诊时间会<b>整列静默变 null</b>，连带列表接诊时间列 / 就诊月份趋势 / 日期范围筛选 /
 * 去重哈希一起退化（2026-09-28 的事故形态）。</p>
 *
 * <p>因此：词典导入（标准术语/别名/国标代码三列纯文本）可以直接用本类；
 * 病历导入要用流式，必须先把处理器扩成「带单元格类型/原始值」（自写 sheet XML 处理器，
 * 从 {@code <c>} 的 {@code s} 取样式、{@code <v>} 取原始值，再判断是否日期格式），
 * 并为日期与紧凑数字列补等价性测试 —— 详见《多批次实施计划》批次 15 的步骤。</p>
 */
public final class ExcelStreamReader {

    private ExcelStreamReader() {
    }

    /** 逐行回调：{@code cells} 已按 {@code cols} 补齐（缺的补 null），行号从 0 起 */
    public interface RowHandler {
        void row(int rowNum, String[] cells);
    }

    /**
     * 流式读第一个工作表，按行回调。
     *
     * @param in     xlsx 输入流（由本方法负责关闭）
     * @param cols   关心的列数（超出部分丢弃，不足补 null）
     * @param handler 逐行回调
     */
    public static void forEachXlsxRow(InputStream in, int cols, RowHandler handler) throws IOException {
        try (OPCPackage pkg = OPCPackage.open(in)) {
            XSSFReader reader = new XSSFReader(pkg);
            StylesTable styles = reader.getStylesTable();
            ReadOnlySharedStringsTable sst = new ReadOnlySharedStringsTable(pkg);
            XMLReader parser = newXmlReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, sst,
                    new CollectingHandler(cols, handler),
                    new org.apache.poi.ss.usermodel.DataFormatter(), false));
            // 只读第一个工作表：与原先 wb.getSheetAt(0) 的口径一致
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
            // SAX / OOXML 的异常都归成 IOException：调用方只需按「这份文件读不了」处理
            throw new IOException("Excel 读取失败：" + e.getMessage(), e);
        }
    }

    /** 命名空间感知的 SAX 解析器；禁用 DOCTYPE（工作表 XML 不需要它，开着等于白送一个 XXE 面） */
    private static XMLReader newXmlReader() throws Exception {
        javax.xml.parsers.SAXParserFactory spf = javax.xml.parsers.SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        try {
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // 某些 JDK 实现不支持该特性：不阻断解析，工作表 XML 本身也不带 DOCTYPE
        }
        return spf.newSAXParser().getXMLReader();
    }

    /** 把 SAX 的单元格事件攒成「一行一个数组」 */
    private static final class CollectingHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int cols;
        private final RowHandler target;
        private String[] current;
        private int rowNum;

        private CollectingHandler(int cols, RowHandler target) {
            this.cols = cols;
            this.target = target;
        }

        @Override
        public void startRow(int rowNum) {
            this.rowNum = rowNum;
            this.current = new String[cols];
        }

        @Override
        public void endRow(int rowNum) {
            String[] cells = current == null ? new String[cols] : current;
            current = null;
            target.row(rowNum, cells);
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (current == null) {
                current = new String[cols];
            }
            int col = columnIndex(cellReference);
            if (col >= 0 && col < cols) {
                // 空单元格给 null（与既有的 cellText 对空单元格的处理一致），便于调用方判「整行全空」；
                // 非空值统一 trim —— 既有 cellText 对字符串单元格也是 trim 后再用
                String v = formattedValue == null ? null : formattedValue.trim();
                current[col] = (v == null || v.isEmpty()) ? null : v;
            }
        }

        @Override
        public void headerFooter(String text, boolean isHeader, String tagName) {
            // 页眉页脚不是数据，忽略
        }
    }

    /** "C7" → 2（0 基列号）；取不出字母时返回 -1 */
    static int columnIndex(String cellReference) {
        if (cellReference == null) {
            return -1;
        }
        int col = 0;
        boolean seenLetter = false;
        List<Character> letters = new ArrayList<>();
        for (int i = 0; i < cellReference.length(); i++) {
            char c = cellReference.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                letters.add(c);
            } else if (c >= 'a' && c <= 'z') {
                letters.add(Character.toUpperCase(c));
            } else {
                break;
            }
        }
        if (letters.isEmpty()) {
            return -1;
        }
        for (char c : letters) {
            col = col * 26 + (c - 'A' + 1);
            seenLetter = true;
        }
        return seenLetter ? col - 1 : -1;
    }
}
