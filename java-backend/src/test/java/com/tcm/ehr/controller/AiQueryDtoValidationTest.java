package com.tcm.ehr.controller;

import com.tcm.ehr.domain.dto.AiQueryDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 三端点共用 DTO 的必填约束归属。
 *
 * <p>事故背景：{@code AiQueryDTO} 被多个端点共用，而「某个端点必填」的约束
 * （如 {@code @NotBlank} / {@code @NotEmpty}）一旦打在共用字段上，
 * 只传其它字段的端点就会<b>在进入 Controller 之前</b>被 Bean Validation 拦掉 ——
 * 用户点的是「AI 预检」，报错却说他没填问题。</p>
 *
 * <p>教训：「某个端点必填」的约束属于<b>该端点</b>，不能放在共用 DTO 上；
 * 跨端点真实存在的上限约束（{@code @Size}）才该留在 DTO。本测试把这条钉死 ——
 * 当前 DTO 上仍在生效的活例子是 {@code terms}（仅 termsuggest 必填，
 * 但「单次最多 50 个」是任何调用方都该守的上限）。</p>
 */
class AiQueryDtoValidationTest {

    private static Validator validator() {
        ValidatorFactory f = Validation.buildDefaultValidatorFactory();
        return f.getValidator();
    }

    private static List<String> terms(int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add("词" + i);
        }
        return out;
    }

    @Test
    @DisplayName("interpret / review 的入参（只有 recordId）必须通过校验")
    void recordIdOnlyRequestPassesValidation() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        // 没有 terms —— interpret 与 review 就是这样调的
        assertTrue(validator().validate(dto).isEmpty(),
                "只传 recordId 的请求不应被判为「请输入待补词」；"
                        + "实际校验错误: " + validator().validate(dto));
    }

    @Test
    @DisplayName("空 recordId 也放行到 Controller（由各端点自己判并给 400）")
    void blankRequestStillReachesController() {
        AiQueryDTO dto = new AiQueryDTO();
        // Controller 里对 recordId 判空并回「未指定病历」，那才是对的提示
        assertTrue(validator().validate(dto).isEmpty());
    }

    @Test
    @DisplayName("terms 超过 50 个仍被拦（@Size 保留 —— 这是跨调用方的真实上限）")
    void oversizedTermsStillRejected() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        dto.setTerms(terms(51));
        assertEquals(1, validator().validate(dto).size(),
                "单次待补词超过 50 个应由 @Size 拦下");
    }

    @Test
    @DisplayName("terms 为空时不由 Bean Validation 拦截（改由 termsuggest 端点自己判）")
    void emptyTermsNotBlockedByBeanValidation() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        // termsuggest 的必填由 AiController.submitAsync 判（回 400「请提供待规范的术语」）；
        // DTO 不再重复要求，否则 interpret / review 会被误伤
        assertTrue(validator().validate(dto).isEmpty());
    }
}
