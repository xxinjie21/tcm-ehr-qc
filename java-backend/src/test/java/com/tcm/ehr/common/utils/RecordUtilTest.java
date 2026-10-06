package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 批F·7.1：去重哈希（21 字段固定顺序 MD5）——同口径、可复现、字段差异可区分。
 */
class RecordUtilTest {

    private Record base() {
        Record r = new Record();
        r.setRegistrationNo("R001");
        r.setOutpatientNo("O001");
        r.setGender("男");
        r.setAge("45");
        r.setWesternDiagnosis("上呼吸道感染");
        r.setTcmDiagnosis("风热感冒");
        r.setPattern("风热犯肺证");
        return r;
    }

    @Test
    void stableAndDeterministic() {
        assertEquals(RecordUtil.textHash(base()), RecordUtil.textHash(base()));
        assertEquals(32, RecordUtil.textHash(base()).length());
    }

    @Test
    void fieldDifferenceChangesHash() {
        Record a = base();
        Record b = base();
        b.setPattern("风寒束表证");
        assertNotEquals(RecordUtil.textHash(a), RecordUtil.textHash(b));
    }

    // ---- column（批次14 审核收敛）：原先 QcScorer 与 QcServiceImpl 各一份 switch ----

    @Test
    void columnCoversTheTwoFieldsOnlyOneSideHad() {
        // 收敛前 QcServiceImpl 那份没有这两个 case：规则引用 department/doctorId 时
        // 评分侧取到值、明细侧静默取到 null（本次要修的 bug）
        Record r = base();
        r.setDepartment("针灸科");
        r.setDoctorId("D100");
        assertEquals("针灸科", RecordUtil.column(r, "department"));
        assertEquals("D100", RecordUtil.column(r, "doctorId"));
    }

    @Test
    void columnTrimsAndTreatsBlankAsMissing() {
        // 原两份实现的空白口径逐字相同：isBlank → null，否则 trim
        Record r = base();
        r.setPulse("  弦细  ");
        assertEquals("弦细", RecordUtil.column(r, "pulse"));
        r.setTongue("   ");
        assertNull(RecordUtil.column(r, "tongue"));
    }

    @Test
    void columnReturnsNullForUnknownFieldAndNullInput() {
        Record r = base();
        assertNull(RecordUtil.column(r, "noSuchField"));
        assertNull(RecordUtil.column(r, null));
        assertNull(RecordUtil.column(null, "pulse"));
    }

    @Test
    void columnCoversAllNineteenMappedFields() {
        // 联合表的完整清单（19 个）：漏一个就等于「规则引用该字段时静默无值」
        Record r = base();
        String[][] pairs = {
                {"registrationNo", "R9"}, {"outpatientNo", "O9"}, {"gender", "女"}, {"age", "50"},
                {"westernDiagnosis", "W"}, {"tcmDiagnosis", "T"}, {"presentIllness", "P"},
                {"chiefComplaint", "C"}, {"selfReport", "S"}, {"inspection", "I"},
                {"pulse", "脉"}, {"tongue", "舌"}, {"physicalExam", "PE"}, {"pattern", "证"},
                {"prescription", "方"}, {"followUp", "随"}, {"treatmentEffect", "效"},
                {"department", "科"}, {"doctorId", "D9"}
        };
        r.setRegistrationNo("R9");
        r.setOutpatientNo("O9");
        r.setGender("女");
        r.setAge("50");
        r.setWesternDiagnosis("W");
        r.setTcmDiagnosis("T");
        r.setPresentIllness("P");
        r.setChiefComplaint("C");
        r.setSelfReport("S");
        r.setInspection("I");
        r.setPulse("脉");
        r.setTongue("舌");
        r.setPhysicalExam("PE");
        r.setPattern("证");
        r.setPrescription("方");
        r.setFollowUp("随");
        r.setTreatmentEffect("效");
        r.setDepartment("科");
        r.setDoctorId("D9");
        for (String[] p : pairs) {
            assertEquals(p[1], RecordUtil.column(r, p[0]), "字段 " + p[0] + " 未映射");
        }
    }
}
