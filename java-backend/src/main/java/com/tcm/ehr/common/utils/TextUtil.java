package com.tcm.ehr.common.utils;

/**
 * 文本小工具（阶段3 P3.2 去重收敛）。
 *
 * {@link #stripCodeFence} 原在 AiServiceImpl 与 DictionaryServiceImpl
 * 各有一份逐字相同的实现 —— 两份代码漂移后，剥错一边就会让整条链路静默解析失败。
 * 统一收敛到此处，两处调用点共用。
 *
 * 2026-10 新增标点规整 {@link #normalizePunctuation} / {@link #stripPunctuationBoundary}：
 * 导出的病历文本与术语归一共用同一套标点口径 —— 原文里有「。。」「，，」这类重复标点、
 * 半角标点或标点旁的游离空格时，统一规整后再使用。该口径只作用于导出与归一，
 * 不改动库里的病历原文。
 */
public final class TextUtil {

    private TextUtil() {
    }

    /** 可折叠标点：连续出现多个时只保留一个（。。→。，、、→、）。刻意不含 … / —（省略号、破折号本就成对） */
    private static final String COLLAPSIBLE_PUNCT = "。，、；：！？";
    /** 中文/全角标点：这些字符两侧的空白统一去掉 */
    private static final String SPACING_PUNCT = "。，、；：！？…—～（）【】《》「」『』“”‘’";
    /** 术语首尾要去掉的中英文标点（术语不该以标点开头/结尾） */
    private static final String EDGE_PUNCT = SPACING_PUNCT + ",.;:!?()[]{}<>\"'";

    /** null / 空白统一判空（5 份私有实现收敛于此，见 P3.2） */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 去掉模型可能加上的 ``` 围栏，只留 JSON 本体。
     *
     * @param s 模型原始返回，可能为 null
     * @return 围栏内的 JSON 文本（trim 过）；非围栏开头则原样 trim 返回
     */
    public static String stripCodeFence(String s) {
        String t = s == null ? "" : s.trim();
        // 1. 不是围栏开头就原样返回
        if (t.startsWith("```")) {
            // 2. 去掉首行 ```lang，再去掉末尾 ```
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        return t.trim();
    }

    /**
     * 标点规整：合并重复标点、半角标点转中文全角、去掉中文标点两侧的空白。
     *
     * 只做「明显不恰当」的规整，不改写文字本身。三条规则：
     * <ol>
     * - 合并重复标点：{@code 。。→。}、{@code ，，，→，}（同一标点连续出现只留一个；
     *       {@code ……} 省略号、{@code ——} 破折号成对出现属正常写法，不折叠）。
     * - 半角 → 全角：{@code , . ; : ! ? ( )} 转成对应中文标点。但仅在紧邻汉字时
     *       转换 —— 否则会把 {@code 体温36.5℃}、{@code 8:30}、{@code 1,000} 里的
     *       {@code . , :} 也改掉，数值被破坏且不可逆。纯英文片段里的标点同样保留。
     * - 去标点两侧空白：{@code 好转 ，继续} → {@code 好转，继续}；标点后的游离空格也去掉。
     * </ol>
     *
     * null / 空串原样返回；单次线性扫描，无正则。
     */
    public static String normalizePunctuation(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int n = s.length();
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            // 1. 半角 → 全角：仅当紧邻汉字（保护数值/英文），或紧跟同批半角标点（如英文 ".." 的后续点）
            if (isConvertibleHalfWidth(c)
                    && (adjacentToCjk(s, i) || (i > 0 && isConvertibleHalfWidth(s.charAt(i - 1))))) {
                c = toFullWidth(c);
            }
            // 2. 空白：中文标点后的空格丢掉，其余（词间空格、换行）保留
            if (c == ' ' || c == '\t') {
                if (out.length() > 0 && SPACING_PUNCT.indexOf(out.charAt(out.length() - 1)) >= 0) {
                    continue;
                }
                out.append(c);
                continue;
            }
            // 3. 中文标点：去它前面的空格；可折叠的再与上一个已输出字符比对去重
            if (SPACING_PUNCT.indexOf(c) >= 0) {
                trimTrailingBlank(out);
                if (COLLAPSIBLE_PUNCT.indexOf(c) >= 0
                        && out.length() > 0 && out.charAt(out.length() - 1) == c) {
                    continue;
                }
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 去掉术语首尾的标点（中英文都算）：{@code 胃痛。。→胃痛}、{@code (发热)→发热}。
     *
     * 供术语归一的候选词使用：抽取到的术语常带尾巴标点，留着会让「精确/包含」判定失准。
     * 只剥首尾，不动中间（{@code 天麻10g，菊花10g} 这种段落不该被当成一个术语清洗）。
     */
    public static String stripPunctuationBoundary(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        int from = 0;
        int to = s.length();
        while (from < to && EDGE_PUNCT.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && EDGE_PUNCT.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }

    private static boolean isConvertibleHalfWidth(char c) {
        return c == ',' || c == '.' || c == ';' || c == ':' || c == '!' || c == '?'
                || c == '(' || c == ')';
    }

    private static char toFullWidth(char c) {
        switch (c) {
            case ',':
                return '，';
            case '.':
                return '。';
            case ';':
                return '；';
            case ':':
                return '：';
            case '!':
                return '！';
            case '?':
                return '？';
            case '(':
                return '（';
            case ')':
                return '）';
            default:
                return c;
        }
    }

    /**
     * 某一位置是否紧邻汉字（前侧或后侧，跳过空白）。
     *
     * 跳过空白是为了让 {@code 发热 , 咳嗽} 这种「标点两侧带空格」的写法也能识别出中文语境：
     * 只看紧挨的一位会漏判（紧挨的是空格），于是半角逗号既没转全角、空格也没去掉。
     */
    private static boolean adjacentToCjk(String s, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (!isBlank(s.charAt(j))) {
                if (isCjk(s.charAt(j))) {
                    return true;
                }
                break;
            }
        }
        for (int k = i + 1; k < s.length(); k++) {
            if (!isBlank(s.charAt(k))) {
                if (isCjk(s.charAt(k))) {
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static void trimTrailingBlank(StringBuilder sb) {
        while (sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last == ' ' || last == '\t') {
                sb.setLength(sb.length() - 1);
            } else {
                break;
            }
        }
    }
}
