package com.tcm.ehr.common.utils;

/**
 * 文本小工具（阶段3 P3.2 去重收敛）。
 *
 * <p>{@link #stripCodeFence} 原在 {@code AiServiceImpl} 与 {@code DictionaryServiceImpl}
 * 各有一份逐字相同的实现 —— 两份代码漂移后，剥错一边就会让整条链路静默解析失败。
 * 统一收敛到此处，两处调用点共用。</p>
 */
public final class TextUtil {

    private TextUtil() {
    }

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
}