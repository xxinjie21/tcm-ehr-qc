package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.Record;
import com.tcm.ehr.domain.vo.SearchVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 病历列表返回体的「评分」字段。
 *
 * <p>批次 13 起分级（{@code grade}）与评分（{@code score}）是<b>分开算、分开给</b>的：
 * 分级是结论、评分是量值。只给分级就看不出「差几分」，复核员与管理员都得再点一次详情。</p>
 *
 * <p>这个字段是 {@code Item} 构造器的最后一个入参，最容易被漏传而<b>编译期不报错</b>
 * （漏传就是 null），所以用测试钉住。</p>
 */
class SearchVoScoreTest {

    private Record record(Integer score) {
        Record r = new Record();
        r.setId("rec-1");
        r.setChiefComplaint("头晕");
        r.setGrade("合格");
        r.setScore(score);
        r.setVisitTime(LocalDateTime.of(2026, 3, 1, 9, 30));
        r.setGender("男");
        r.setAge("42");
        return r;
    }

    @Test
    @DisplayName("构造器完整传参时，score 落到返回体上")
    void scoreIsCarriedThrough() {
        SearchVO.Item item = new SearchVO.Item("rec-1", "头晕", "合格",
                LocalDateTime.of(2026, 3, 1, 9, 30), "男", "42", 92);

        assertNotNull(item.getScore(), "score 没落到返回体");
        assertEquals(92, item.getScore());
        assertEquals("合格", item.getGrade(), "加了 score 不能挤掉原有的 grade");
        assertEquals("男", item.getGender());
        assertEquals("42", item.getAge());
    }

    @Test
    @DisplayName("未质控的病历：score 为 null 而不是 0")
    void nullScoreStaysNull() {
        // 0 与 null 含义不同：null=没质控过，0=质控过但零分。
        // 用 0 顶替会把「还没评分」显示成「0 分」，看着像被判了最差。
        SearchVO.Item item = new SearchVO.Item("rec-1", "头晕", null,
                LocalDateTime.now(), "男", "42", null);

        assertNull(item.getScore());
    }

    @Test
    @DisplayName("字段名与类型：score 为可空 Integer，与 records.score 列一致")
    void fieldNameAndTypeMatchColumn() throws NoSuchFieldException {
        // 前端按 row.score 取值；字段名变了前端会静默拿不到值
        java.lang.reflect.Field f = SearchVO.Item.class.getDeclaredField("score");
        assertEquals(Integer.class, f.getType(), "score 应为可空 Integer，与 records.score 列一致");
    }
}