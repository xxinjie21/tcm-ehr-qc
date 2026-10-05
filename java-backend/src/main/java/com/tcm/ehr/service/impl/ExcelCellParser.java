package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.TextUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Excel 单元格 → 值（批次 13 · 13.3 的第二个抽取对象）。
 *
 * <p><b>为什么抽它</b>：这一组（取文本 + 接诊时间解析）是「把一个单元格变成 Java 值」这一件事，
 * 与病历的业务逻辑无关，也不碰数据库。抽出来后 {@code RecordServiceImpl} 只负责业务编排。</p>
 *
 * <p>核心是接诊时间的归一：导入源这一列是 14 位紧凑数字串（{@code 20221224090613}），
 * 单元格类型为数值、格式为 General —— 既不是日期格式、也不含分隔符。只按
 * {@code yyyy-MM-dd HH:mm:ss} 硬解析会全部落到 null，表现为列表「接诊时间」整列空白。
 * 这里统一收口五类写法：</p>
 *
 * <ul>
 * <li>纯数字紧凑串 8 / 12 / 14 位：{@code 20221224} / {@code 202212240906} / {@code 20221224090613}</li>
 * <li>中文年月日：{@code 2022年12月24日}</li>
 * <li>斜杠与点分隔：{@code 2022/12/24} / {@code 2022.12.24}</li>
 * <li>缺省部分：只到日补 {@code 00:00:00}，只到分补 {@code :00}</li>
 * <li>多余部分：ISO 的 {@code T} 换成空格，小数秒与时区后缀截掉</li>
 * </ul>
 *
 * <p>认不出来的一律返回 null（而不是给一个「看起来合法」的时间）：宁可缺接诊时间，
 * 也不要让整行导入失败，更不要把错误时间写进病历。</p>
 */
final class ExcelCellParser {

    /** 与 normalizeDateTime 的输出同形；断言归一结果必须能被它解析，否则等于白归一 */
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern COMPACT_DT = Pattern.compile("\\d{8}|\\d{12}|\\d{14}");

    private ExcelCellParser() {
    }

    /**
     * 标准格式的 formatter（{@code yyyy-MM-dd HH:mm:ss}）。
     *
     * <p>暴露它是为了让调用方写 {@code LocalDateTime.parse(normalizeDateTime(s), ExcelCellParser.formatter())} ——
     * 与搬家前的写法**逐字等价**（包括「解析失败抛异常」这一点），
     * 而不是换成「返回 null」的版本，那会悄悄改掉调用方的错误处理。</p>
     */
    static DateTimeFormatter formatter() {
        return DT;
    }

    /** 接诊时间：支持 Excel 日期数值、紧凑数字串与常见字符串格式；认不出来给 null */
    static LocalDateTime parseDateTime(Cell cell) {
        // 1. 空单元格给 null
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        // 2. Excel 真正的日期型单元格直接取值，避开时区与格式转换
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue();
        }
        // 3. 其余按文本处理
        String s = cellText(cell);
        if (TextUtil.isBlank(s)) {
            return null;
        }
        try {
            // 4. 先归一为标准格式，再统一解析
            return LocalDateTime.parse(normalizeDateTime(s), DT);
        } catch (Exception e) {
            // 5. 格式不认识给 null：宁可缺接诊时间，也不要让整行导入失败
            return null;
        }
    }

    /** 把「接诊时间」单元格文本归一为 {@code yyyy-MM-dd HH:mm:ss}（详见类注释） */
    static String normalizeDateTime(String raw) {
        // 1. 去首尾空白、ISO 的 T 换空格、连续空白压成一个
        String s = raw.trim().replace('T', ' ').replaceAll("\\s+", " ");
        // 2. 纯数字紧凑串：8 位到日 / 12 位到分 / 14 位到秒
        if (COMPACT_DT.matcher(s).matches()) {
            String date = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
            String hourMinute = s.length() >= 12 ? s.substring(8, 10) + ":" + s.substring(10, 12) : "00:00";
            String second = s.length() == 14 ? s.substring(12, 14) : "00";
            return date + " " + hourMinute + ":" + second;
        }
        // 3. 以第一个空格拆日期段与时间段
        int sp = s.indexOf(' ');
        String date = sp < 0 ? s : s.substring(0, sp);
        String time = sp < 0 ? "" : s.substring(sp + 1);
        // 4. 日期段：中文年月日与斜杠点都换成短横，再把月日补成两位
        date = padDate(date.replace("年", "-").replace("月", "-").replace("日", "")
                .replace('/', '-').replace('.', '-'));
        // 5. 时间段：按冒号拆成 时:分:秒，逐段补零、缺段补 00，多余部分（小数秒/时区）截掉
        String[] t = time.isEmpty() ? new String[0] : time.split(":");
        String hh = t.length > 0 ? pad2(t[0]) : "00";
        String mm = t.length > 1 ? pad2(t[1]) : "00";
        String ss = t.length > 2 ? pad2(t[2].length() > 2 ? t[2].substring(0, 2) : t[2]) : "00";
        return date + " " + hh + ":" + mm + ":" + ss;
    }

    /** 日期段补零：{@code 2022-1-2} → {@code 2022-01-02}；不是三段或年份不足四位则原样返回 */
    private static String padDate(String date) {
        String[] p = date.split("-");
        if (p.length != 3 || p[0].length() != 4) {
            return date;
        }
        return p[0] + "-" + pad2(p[1]) + "-" + pad2(p[2]);
    }

    /** 一位数补成两位，其余原样；非数字留给后续 parse 抛错兜住 */
    private static String pad2(String v) {
        return v.length() == 1 ? "0" + v : v;
    }

    /** 单元格取文本：按显示格式取值，数字/日期型统一转字符串 */
    static String cellText(Cell cell) {
        // 1. 空单元格给 null
        if (cell == null) {
            return null;
        }
        // 2. 按单元格类型取值
        return switch (cell.getCellType()) {
            case STRING -> {
                String v = cell.getStringCellValue();
                yield v == null || v.isBlank() ? null : v.trim();
            }
            case NUMERIC -> {
                double d = cell.getNumericCellValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield String.valueOf((long) d);
                }
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }
}
