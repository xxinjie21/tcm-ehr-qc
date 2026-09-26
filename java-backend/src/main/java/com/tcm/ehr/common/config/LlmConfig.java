package com.tcm.ehr.common.config;

/**
 * LLM 生效配置的一份快照。
 *
 * <p>与 {@link LlmProperties}（{@code application.yml} 基线）区分：本记录表示<b>当前实际生效</b>的参数，
 * 可能来自配置文件，也可能来自运行时覆盖（topbar「导入 LLM」弹窗）。覆盖只改内存、不写回
 * {@code application.yml}，因此重启后回到配置文件基线。</p>
 */
public record LlmConfig(boolean enabled, String provider, String baseUrl, String apiKey,
                        String model, Double temperature, int timeout) {

    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_OPENAI = "openai";

    /** 从配置文件基线取值 */
    public static LlmConfig from(LlmProperties p) {
        return new LlmConfig(p.isEnabled(), p.getProvider(), p.getBaseUrl(), p.getApiKey(),
                p.getModel(), p.getTemperature(), p.getTimeout());
    }

    /**
     * provider 归一化（去空白 + 小写）。
     *
     * @return 合法值 {@code ollama} / {@code openai}；其他返回 {@code null}
     */
    public static String normalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String v = provider.trim().toLowerCase();
        return (PROVIDER_OLLAMA.equals(v) || PROVIDER_OPENAI.equals(v)) ? v : null;
    }
}
