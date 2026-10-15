package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 否定语境拦截的守护测试：锁住「**拦下否证句，不拦干净命中**」这条不变量。
 *
 * <p><b>为什么必须有这个拦截</b>：二级·包含判定是双向包含且没有长度保护
 * （{@link EsTermNormalizer#judge} 的 {@code input.contains(std)}），
 * 于是含某个标准词的长描述句也会命中。症状域里这类句子大量是<b>否证</b>：
 * 「无明显流涎」命中「流涎」、「带下已干净」命中「带下」。
 * 归一后语义正好反过来 —— 病人说没有，结构化数据记成有。
 * 更糟的是 {@code QcScorer.countUnnormalized} 把「无 normLevel」算未标准化，
 * 所以否证句被误归一会**倒加分**：否证越多的病历反而显得越规范。</p>
 *
 * <p><b>实测规模</b>（2026-10-15，35352 条真实病历）：补 109 个规范症状词后，
 * 若不拦截会有 4867 条否证/已愈句被误归一；拦截后干净命中 18874 条不受影响。</p>
 *
 * <p>三条断言对应三种情形：拦（否证句）· 不拦（干净包含）· 不拦（精确命中）。</p>
 */
class EntityNormalizerNegationGuardTest {

    private static final String ORG = "org-A";

    /** 症状一律按「包含命中」返回 —— 这是最容易被否证句钻空子的那一层 */
    private static EsTermNormalizer containHitNormalizer() {
        EsTermNormalizer n = mock(EsTermNormalizer.class);
        when(n.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(2);
            return new EsTermNormalizer.NormalizeResult(raw, EsTermNormalizer.LEVEL_CONTAIN);
        });
        return n;
    }

    private static EntityNormalizer normalizer(EsTermNormalizer term) {
        return new EntityNormalizer(term, new ObjectMapper());
    }

    private static NlpExtractVO voWithSymptoms(String... raws) {
        NlpExtractVO vo = new NlpExtractVO();
        for (String r : raws) {
            NlpExtractVO.Entity e = new NlpExtractVO.Entity();
            e.setContent(r);
            e.setSourceText(r);
            vo.getSymptoms().add(e);
        }
        return vo;
    }

    private static List<Integer> levels(List<NlpExtractVO.Entity> list) {
        List<Integer> out = new ArrayList<>();
        for (NlpExtractVO.Entity e : list) {
            out.add(e.getNormLevel());
        }
        return out;
    }

    @Test
    void negatedSentencesAreNotNormalized() {
        EntityNormalizer en = normalizer(containHitNormalizer());
        // 3 条否证句：症状词都在，但整句是对该症状的否证 / 已愈
        NlpExtractVO vo = voWithSymptoms("无明显流涎", "带下已干净", "便前已无肠鸣");

        en.normalize(vo, ORG);

        // 违反会怎样：三条都会被归成症状，语义反转；且因写了 normLevel，
        // QcScorer 会认为「术语已标准化」⇒ 质控倒加分。
        assertEquals(Arrays.asList(null, null, null), levels(vo.getSymptoms()));
        assertTrue(en.drainNegatedSkip() > 0, "否证句必须计入 negatedSkip，否则报表无法对账");
    }

    @Test
    void plainContainmentHitsStillNormalize() {
        EntityNormalizer en = normalizer(containHitNormalizer());
        // 干净命中：症状词就是句子的主体，没有否定语素
        NlpExtractVO vo = voWithSymptoms("汗出彻全身", "食多易胃胀", "腰酸痛加重");

        en.normalize(vo, ORG);

        // 违反会怎样：否定词保护把该归的也拦了，补词表的收益直接归零
        assertEquals(List.of(EsTermNormalizer.LEVEL_CONTAIN,
                EsTermNormalizer.LEVEL_CONTAIN,
                EsTermNormalizer.LEVEL_CONTAIN),
                levels(vo.getSymptoms()));
        assertEquals(0, en.drainNegatedSkip(), "干净命中不该进拦截计数");
    }

    @Test
    void exactHitsAreNeverBlocked() {
        EsTermNormalizer n = mock(EsTermNormalizer.class);
        when(n.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String raw = inv.getArgument(2);
            // 「咽不红」精确命中：它本身就是含否定字的正式条目（舌象诊疗用语）
            return new EsTermNormalizer.NormalizeResult(raw, EsTermNormalizer.LEVEL_EXACT);
        });
        EntityNormalizer en = normalizer(n);
        NlpExtractVO vo = voWithSymptoms("咽不红");

        en.normalize(vo, ORG);

        // 违反会怎样：词典里正式收录的否定式症状被拦，是丢既有行为（线上已有 36 条依赖它）
        assertEquals(List.of(EsTermNormalizer.LEVEL_EXACT), levels(vo.getSymptoms()));
        assertEquals(0, en.drainNegatedSkip());
    }

    @Test
    void isNegatedCoversFixedNegativeForms() {
        // 判据逐条钉住：改判定口径会静默改变拦截范围，故把每种形态写在这里
        assertTrue(EntityNormalizer.isNegated("无明显流涎"));
        assertTrue(EntityNormalizer.isNegated("无夜寐流涎"));
        assertTrue(EntityNormalizer.isNegated("未观察"));
        assertTrue(EntityNormalizer.isNegated("莫"));

        // 「已 + 痊愈类」没有否定字，单字扫描抓不到，靠第二段判据
        assertTrue(EntityNormalizer.isNegated("带下已干净"));
        assertTrue(EntityNormalizer.isNegated("皮疹已无新发"));   // 「无」已覆盖，双保险

        // 反面：不含否定语素的普通症状描述
        assertFalse(EntityNormalizer.isNegated("鼻塞"));
        assertFalse(EntityNormalizer.isNegated("汗出彻全身"));
        assertFalse(EntityNormalizer.isNegated("腰酸痛加重"));
        assertFalse(EntityNormalizer.isNegated(null));
        assertFalse(EntityNormalizer.isNegated(""));
    }
}
