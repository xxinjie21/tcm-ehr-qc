package com.tcm.ehr.common.config;

/**
 * LLM 生效配置的一份快照。
 *
 * <p>与 {@link LlmProperties}（{@code application.yml} 基线）区分：本记录表示<b>当前实际生效</b>的参数，
 * 可能来自配置文件，也可能来自运行时覆盖（topbar「导入 LLM」弹窗）。覆盖只改内存、不写回
 * {@code application.yml}，因此重启后回到配置文件基线。</p>
 */
public record LlmConfig(boolean enabled, String provider, String baseUrl, String apiKey,
                        String model, Double temperature, int timeout,
                        boolean interpretEnabled, boolean reviewEnabled, boolean termSuggestEnabled) {

    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    /**
     * 不含功能开关的构造：所有功能默认跟随总开关开启。
     *
     * <p>保留它是为了让「只关心连接参数」的调用点（连通性探测、单元测试）
     * 不必为三个开关操心 —— 它们本来就不该管功能维度的启停。</p>
     */
    public LlmConfig(boolean enabled, String provider, String baseUrl, String apiKey,
                     String model, Double temperature, int timeout) {
        this(enabled, provider, baseUrl, apiKey, model, temperature, timeout, true, true, true);
    }

    /** 从配置文件基线取值 */
    public static LlmConfig from(LlmProperties p) {
        return new LlmConfig(p.isEnabled(), p.getProvider(), p.getBaseUrl(), p.getApiKey(),
                p.getModel(), p.getTemperature(), p.getTimeout(),
                p.isInterpretEnabled(), p.isReviewEnabled(), p.isTermSuggestEnabled());
    }

    /**
     * 某个 AI 功能是否用 LLM：总开关未开一律 false（局部开关不能越过总开关）。
     */
    public boolean featureEnabled(AiFeature feature) {
        if (!enabled) {
            return false;
        }
        return switch (feature) {
            case INTERPRET -> interpretEnabled;
            case REVIEW -> reviewEnabled;
            case TERM_SUGGEST -> termSuggestEnabled;
        };
    }

    /**
     * provider 归一化（去空白 + 小写）。
     *
     * @return 合法值 {@code ollama} / {@code openai}；其他返回 {@code null}
     */
    public static String normalizeProvider(String provider) {
        // 1. 空值给 null（由调用方给默认值或报错）
        if (provider == null) {
            return null;
        }
        // 2. 去空白并统一小写，容忍 "OpenAI" / " openai " 这类输入
        String v = provider.trim().toLowerCase();
        // 3. 不认识的通道给 null，不猜
        return (PROVIDER_OLLAMA.equals(v) || PROVIDER_OPENAI.equals(v)) ? v : null;
    }
}
