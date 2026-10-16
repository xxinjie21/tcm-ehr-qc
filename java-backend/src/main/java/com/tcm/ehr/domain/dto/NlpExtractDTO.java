package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * NLP 抽取请求。
 *
 * @param text 待抽取文本；空/全空白由 Bean Validation 直接拦成 400（批次 26.5）。
 *             长度上限 {@code 20000} 与 Python 侧 {@code main.py} 的 {@code MAX_TEXT_CHARS} 同值：
 *             此前不设限，超长文本会转发到 Python 拿 422 再降级，而 Java 侧降级原因只有
 *             「连不上 / 未启用」两种（{@code NlpController.doExtract}），用户看到的是
 *             「服务不可用」而非「文本太长」—— 在入口拦下才能给出准确提示。
 */
public record NlpExtractDTO(
        @NotBlank(message = "请输入待抽取文本")
        @Size(max = 20000, message = "文本最长 20000 字符") String text) {
}
