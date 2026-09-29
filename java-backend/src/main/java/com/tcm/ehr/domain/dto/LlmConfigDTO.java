package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * LLM 配置入参。
 *
 * <p>字段为「可空 = 沿用当前值」语义：前端弹窗只提交用户改过的项也不会把其他项清空。
 * {@code apiKey} 传空串或回传掩码均表示<b>不修改</b>密钥，避免用户看不到明文就无法保存。</p>
 */
@Data
public class LlmConfigDTO {

    /** 是否启用 LLM；null = 沿用当前 */
    private Boolean enabled;

    /** 通道：ollama | openai；null / 空 = 沿用当前 */
    @Pattern(regexp = "^(ollama|openai)?$", message = "通道只能是 ollama 或 openai")
    private String provider;

    /** 接口地址；留空用通道默认。非空时必须是 http(s) 绝对地址 ——
     *  这是服务端出站请求的目标（SSRF 面），非法地址直接拒在入口 */
    @Pattern(regexp = "^$|^https?://[^\\s/]+(/.*)?$",
            message = "接口地址必须是 http:// 或 https:// 开头的绝对地址")
    @Size(max = 300, message = "接口地址最长 300 字")
    private String baseUrl;

    /** api-key；空串或掩码 = 不修改 */
    @Size(max = 500, message = "密钥最长 500 字")
    private String apiKey;

    /** 模型名；留空用通道默认模型 */
    @Size(max = 100, message = "模型名最长 100 字")
    private String model;

    /** 采样温度（0~2） */
    @DecimalMin(value = "0.0", message = "采样温度不能小于 0")
    @DecimalMax(value = "2.0", message = "采样温度不能大于 2")
    private Double temperature;

    /** 单次请求超时（毫秒） */
    @Min(value = 1000, message = "超时不能小于 1000 毫秒")
    @Max(value = 600000, message = "超时不能大于 600000 毫秒（10 分钟）")
    private Integer timeout;
}
