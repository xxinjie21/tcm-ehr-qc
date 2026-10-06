package com.tcm.ehr.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 终末质控评分请求。
 *
 * <p>recordId 必填：服务端目前只按 recordId 载入病历（{@code QcServiceImpl.score} 走
 * {@code loadRaw(recordId)} 后取 {@code raw.getStructuredData()}），请求内联的
 * {@code structuredData} 并不参与评分，故不再宣称「两者至少提供一个」。
 * 空 recordId 由 Bean Validation 直接拦成 400（批次 26.5），文案与服务端守卫一致。</p>
 */
@Data
public class QcScoreDTO {

    @NotBlank(message = "请提供病历标识，或直接提交已抽取的结构化数据（两者至少给一项）")
    private String recordId;
    private Object structuredData;
    private Object qcResults;
}
