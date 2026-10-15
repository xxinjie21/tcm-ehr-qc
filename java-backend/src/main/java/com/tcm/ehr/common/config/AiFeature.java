package com.tcm.ehr.common.config;

/**
 * 可单独启停的 AI 功能。
 *
 * <p>与「启用 LLM」总开关分工：总开关决定「这个用户到底用不用 LLM」，
 * 本枚举决定「哪一项 AI 能力用 LLM」—— 关掉某一项时，该项回退规则/模板兜底，
 * 其余项不受影响。</p>
 *
 * <p>用时也会被 {@link LlmConfig#featureEnabled(AiFeature)} 做「该功能是否开启」的判定，
 * 所以常量本身必须稳定（key 会落到前端入参与用户配置里）。</p>
 */
public enum AiFeature {

    /** 病历解读 */
    INTERPRET("interpret", "AI 解读"),

    /** 复核预检意见 */
    REVIEW("review", "复核意见"),

    /** 术语补词建议 */
    TERM_SUGGEST("termSuggest", "术语建议");

    private final String key;
    private final String label;

    AiFeature(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 稳定键名：DTO / 前端入参都用它标识功能 */
    public String key() {
        return key;
    }

    /** 中文名：供前端开关标签与日志使用 */
    public String label() {
        return label;
    }
}
