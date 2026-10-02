package com.tcm.ehr.controller;

import com.tcm.ehr.domain.dto.AiQueryDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 三端点共用 DTO 的必填约束归属。
 *
 * <p>事故背景：{@code AiQueryDTO} 被 interpret / chat / review <b>三个端点共用</b>，
 * 而 {@code question} 上打了 {@code @NotBlank}。interpret（AI 预检）与 review
 * 只传 recordId、不传 question —— 于是它们<b>在进入 Controller 之前</b>就被
 * Bean Validation 拦掉，页面上反复弹「请输入你的问题」。用户根本没提问，
 * 他点的是「AI 预检」，报错却说他没填问题。</p>
 *
 * <p>教训：「某个端点必填」的约束属于<b>该端点</b>，不能放在共用 DTO 上。
 * 本测试把这条钉死。</p>
 */
class AiQueryDtoValidationTest {

    private static Validator validator() {
        ValidatorFactory f = Validation.buildDefaultValidatorFactory();
        return f.getValidator();
    }

    @Test
    @DisplayName("interpret / review 的入参（只有 recordId）必须通过校验")
    void recordIdOnlyRequestPassesValidation() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        // 没有 question —— interpret 与 review 就是这样调的
        assertTrue(validator().validate(dto).isEmpty(),
                "只传 recordId 的请求不应被判为「请输入你的问题」；"
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
    @DisplayName("question 超长仍被拦（@Size 保留 —— 这是跨端点的真实约束）")
    void oversizedQuestionStillRejected() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        dto.setQuestion("问".repeat(2001));
        assertEquals(1, validator().validate(dto).size(),
                "超长问题应由 @Size 拦下，只是消息文案随语种调整");
    }

    @Test
    @DisplayName("question 为空时不再由 Bean Validation 拦截（改由 chat 端点自己判）")
    void emptyQuestionNotBlockedByBeanValidation() {
        AiQueryDTO dto = new AiQueryDTO();
        dto.setRecordId("rec-1");
        // chat 的必填由 AiController.chat 判（回 400「请输入问题」）与
        // AiServiceImpl 再判一次；DTO 不再重复要求
        assertTrue(validator().validate(dto).isEmpty());
    }
}