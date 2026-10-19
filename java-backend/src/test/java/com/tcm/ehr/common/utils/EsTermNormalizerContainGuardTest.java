package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 判定护栏（R1~R4 + R6 + R7）。反例全部来自 2026-10-18 全流程实测，一半以上会**污染导出文本**。
 *
 * <p>为什么阈值判据要写死在这里：《干脚气》与《广藿香》都是「标准词比输入长」，形态上
 * 无法区分谁该认谁不该认 —— 所以标准词方向的包含一律不认（R2），语义等价改由别名表登记。
 * 这个决定必须有测试钉住，否则下次有人「优化」一下就悄悄放开了。</p>
 */
class EsTermNormalizerContainGuardTest {

    private IEsTermIndexService es;
    private EsTermNormalizer normalizer;

    @BeforeEach
    void setUp() {
        es = mock(IEsTermIndexService.class);
        normalizer = new EsTermNormalizer(es);
        ReflectionTestUtils.setField(normalizer, "scoreThreshold", 0.8);
        ReflectionTestUtils.setField(normalizer, "containDelta", 1);
    }

    private void recalls(TermEntry... entries) throws IOException {
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of(entries));
    }

    private static TermEntry entry(String std, List<String> aliases) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(std);
        e.setAliases(aliases);
        return e;
    }

    @Test
    void singleCharInputNeverContains() throws IOException {
        recalls(entry("有汗", List.of("汗出", "有汗出", "自汗")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "有").level());
    }

    @Test
    void standardTermDirectionSubstringIsRejected() throws IOException {
        recalls(entry("干脚气", List.of()));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("disease", "脚气").level(),
                "「脚气」不是「干脚气」：标准词方向的包含一律不认");
    }

    @Test
    void aliasDirectionSubstringIsRejected() throws IOException {
        recalls(entry("食疗", List.of("食治疗法")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("treatment", "治疗").level(),
                "别名「食治疗法」含「治疗」不构成命中");
        recalls(entry("流清涕", List.of("流清涕反复发作", "清涕")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "反复发作").level());
    }

    @Test
    void longerInputWithinDeltaStillContains() throws IOException {
        // 差 1 字（炮制前缀）仍算包含
        recalls(entry("苍术", List.of("茅术")));
        assertEquals(EsTermNormalizer.LEVEL_CONTAIN, normalizer.normalize("herb", "炒苍术").level(),
                "差 1 字以内仍算包含（保留既有能力）");
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("herb", "麸炒苍术片").level(),
                "差超过阈值、且与标准词构成子串关系 → 不得命中");
    }

    @Test
    void fuzzyLevelRejectsSubstringRelation() throws IOException {
        // R7：Dice=0.8 也不得让「输入 ⊂ 标准词」靠模糊级命中（这是 R2 挡不住的那一半）
        recalls(entry("干脚气", List.of()));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("disease", "脚气").level(),
                "干脚气 ⊃ 脚气，Dice 恰好 0.8，但子串关系不许走模糊级");
        recalls(entry("广藿香", List.of("藿香叶")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("herb", "藿香").level(),
                "广藿香 ⊃ 藿香（别名藿香叶 ⊃ 藿香）→ 同样否决");
        // 非子串关系的真·模糊命中必须保留
        recalls(entry("脉弦细", List.of()));
        assertEquals(EsTermNormalizer.LEVEL_FUZZY, normalizer.normalize("pulse", "脉较细弦").level(),
                "无子串关系、Dice=0.8 的合法模糊命中不能被误伤");
    }

    @Test
    void exactStandardTermBeatsAliasOfLongerTerm() throws IOException {
        // 召回顺序刻意把「别名命中」放在前面：这正是 心悸→心悸手抖 / 知饥→知饥不食 的成因
        recalls(entry("心悸手抖", List.of("心慌手抖", "心悸")), entry("心悸", List.of("心慌")));
        assertEquals("心悸", normalizer.normalize("symptom", "心悸").standardTerm(),
                "标准词精确命中优先于别名精确命中");
        recalls(entry("知饥不食", List.of("饥不欲食", "知饥")), entry("知饥", List.of("易饥饿")));
        assertEquals("知饥", normalizer.normalize("symptom", "知饥").standardTerm());
    }

    @Test
    void fuzzyLevelRejectsPolarityDiff() throws IOException {
        // 实测 2026-10-19：咽不痛→咽痛、大便成形→便溏、食欲欠佳→能食，全部语义相反
        recalls(entry("咽痛", List.of("咽喉疼痛")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "咽不痛").level(),
                "「咽不痛」与「咽痛」仅差一个否定字，模糊级不得命中");

        recalls(entry("便溏", List.of("大便溏", "大便不成形")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "大便成形").level(),
                "「大便成形」是「大便不成形」的反义，模糊级不得命中");

        recalls(entry("能食", List.of("食欲佳", "纳可")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "食欲欠佳").level(),
                "「食欲欠佳」与「食欲佳」仅差一个减弱字，模糊级不得命中");
    }

    @Test
    void fuzzyPolarityVetoDoesNotBlockExactOrOtherAliases() throws IOException {
        recalls(entry("便溏", List.of("大便溏", "大便不成形")));
        assertEquals(EsTermNormalizer.LEVEL_EXACT, normalizer.normalize("symptom", "大便不成形").level(),
                "别名原样命中不受极性否决影响");
        recalls(entry("口干", List.of("口干欲饮")));
        assertEquals(EsTermNormalizer.LEVEL_EXACT, normalizer.normalize("symptom", "口干").level(),
                "标准词原样命中不受影响");
    }

    @Test
    void singleCharAliasNeverContains() throws IOException {
        // R8（2026-10-19）：单字别名只用于召回/精确命中。差额阈值是 ≤1，
        // 1 字别名放进包含判定必然吃掉任何含该字的 2 字串。
        recalls(entry("脉软", List.of("软")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("pulse", "质软").level(),
                "「质软」是舌质描述，不是脉软");
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("pulse", "软条").level(),
                "「软条」是便质描述，不是脉软");
        assertEquals(EsTermNormalizer.LEVEL_EXACT, normalizer.normalize("pulse", "软").level(),
                "单字输入仍然精确命中（R1 的那一半能力保留）");

        recalls(entry("呕吐", List.of("吐", "呕")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "吐痰").level(),
                "「吐痰」不是呕吐");
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "呕血").level(),
                "「呕血」不是呕吐");

        recalls(entry("斑疹", List.of("斑", "疹")));
        assertEquals(EsTermNormalizer.LEVEL_NONE, normalizer.normalize("symptom", "湿疹").level(),
                "「湿疹」是病名，不是斑疹");
    }
}
