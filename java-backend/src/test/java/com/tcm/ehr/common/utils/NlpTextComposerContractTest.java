package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 抽取输入拼装契约：顺序即模型看到的叙述顺序，改动必须被测试拦住。 */
class NlpTextComposerContractTest {

    @Test
    void textFieldsOrderIsFixed() {
        assertEquals(List.of("chiefComplaint", "selfReport", "presentIllness", "inspection",
                        "tongue", "pulse", "physicalExam", "tcmDiagnosis", "pattern",
                        "prescription", "followUp", "treatmentEffect"),
                NlpTextComposer.TEXT_FIELDS, "12 字段顺序是模型输入的契约，不能随手调");
    }

    @Test
    void composeJoinsByFullStopAndSkipsBlank() {
        Record r = new Record();
        r.setChiefComplaint("带下量多");
        r.setTongue("舌质偏暗");
        r.setPulse("   ");
        assertEquals("带下量多。舌质偏暗。", NlpTextComposer.compose(r));
    }

    @Test
    void composeHandlesNullRecord() {
        assertEquals("", NlpTextComposer.compose(null));
    }
}
