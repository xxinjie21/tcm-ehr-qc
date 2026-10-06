package com.tcm.ehr.controller;

import com.tcm.ehr.domain.dto.DeleteRecordsDTO;
import com.tcm.ehr.domain.dto.NlpExtractDTO;
import com.tcm.ehr.domain.dto.NormalizeDTO;
import com.tcm.ehr.domain.dto.QcScoreDTO;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 26.5：把「本来就在服务端判空」的入口改成 Bean Validation 前置。
 *
 * <p>原则是<b>不严于现有服务端守卫</b>：只给已有判空逻辑的字段补约束，
 * 且 message 与守卫文案逐字一致（两条出口都是 HTTP 400 + code=400，
 * 见 {@code GlobalExceptionHandler}），因此对使用者是纯提前、不改语义。
 * 没有任何服务端守卫的字段（FiltersDTO / LogicCheckDTO / QcBatchDTO 等）
 * 一律不补，避免凭空加严。</p>
 */
class RequiredFieldDtoValidationTest {

    private static Validator validator() {
        ValidatorFactory f = Validation.buildDefaultValidatorFactory();
        return f.getValidator();
    }

    private static <T> void assertSingle(Set<ConstraintViolation<T>> v, String expectedMessage) {
        assertEquals(1, v.size(), "应恰好触发一条约束，实际: " + v);
        assertEquals(expectedMessage, v.iterator().next().getMessage());
    }

    @Test
    @DisplayName("DeleteRecordsDTO.ids 为空 → 未选择要操作的病历")
    void deleteRecordsRequiresIds() {
        DeleteRecordsDTO empty = new DeleteRecordsDTO(null);
        assertSingle(validator().validate(empty), "未选择要操作的病历");

        DeleteRecordsDTO blank = new DeleteRecordsDTO(List.of());
        assertSingle(validator().validate(blank), "未选择要操作的病历");

        DeleteRecordsDTO ok = new DeleteRecordsDTO(List.of("rec-1"));
        assertTrue(validator().validate(ok).isEmpty());
    }

    @Test
    @DisplayName("NlpExtractDTO.text 为空/全空白 → 请输入待抽取文本")
    void nlpExtractRequiresText() {
        assertSingle(validator().validate(new NlpExtractDTO(null)), "请输入待抽取文本");

        assertSingle(validator().validate(new NlpExtractDTO("   ")), "请输入待抽取文本");

        assertTrue(validator().validate(new NlpExtractDTO("患者发热咳嗽两天")).isEmpty());
    }

    @Test
    @DisplayName("NormalizeDTO.term 为空 → 请输入术语（type 合法性仍由 Controller 判 4001）")
    void normalizeRequiresTerm() {
        assertSingle(validator().validate(new NormalizeDTO(null, null)), "请输入术语");

        assertTrue(validator().validate(new NormalizeDTO("symptom", "嗓子疼")).isEmpty());
    }

    @Test
    @DisplayName("QcScoreDTO.recordId 为空 → 与服务端守卫同文案")
    void qcScoreRequiresRecordId() {
        assertSingle(validator().validate(new QcScoreDTO()),
                "请提供病历标识，或直接提交已抽取的结构化数据（两者至少给一项）");

        QcScoreDTO ok = new QcScoreDTO();
        ok.setRecordId("rec-1");
        assertTrue(validator().validate(ok).isEmpty());
    }
}
