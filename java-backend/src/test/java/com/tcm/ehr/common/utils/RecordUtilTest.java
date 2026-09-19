package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.Record;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
