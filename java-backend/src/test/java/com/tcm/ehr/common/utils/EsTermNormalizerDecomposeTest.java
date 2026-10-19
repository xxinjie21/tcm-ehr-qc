package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 组合短语拆解的守护测试（2026-10-19）。
 *
 * <p>口径：脉象「左关偏浮」= 脉位 + 脉质；舌象「舌淡苔薄」= 舌质 + 苔质。
 * 只有整串被词典项完整覆盖才拆，任何无落点字（含否定 / 关系字）都整体放弃。
 */
class EsTermNormalizerDecomposeTest {

    private static final String ORG = "grp-default-2026";

    private IEsTermIndexService es;
    private EsTermNormalizer normalizer;

    @BeforeEach
    void setUp() {
        es = Mockito.mock(IEsTermIndexService.class);
        normalizer = new EsTermNormalizer(es);
        ReflectionTestUtils.setField(normalizer, "scoreThreshold", 0.8);
        ReflectionTestUtils.setField(normalizer, "containDelta", 1);
    }

    private static TermEntry entry(String standardTerm, String... aliases) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(standardTerm);
        e.setAliases(List.of(aliases));
        return e;
    }

    private void recallPulse() throws IOException {
        List<TermEntry> candidates = List.of(
                entry("左寸"), entry("左关"), entry("左尺"),
                entry("右寸"), entry("右关"), entry("右尺"),
                entry("脉浮"), entry("脉沉"), entry("脉弱"), entry("脉弦"),
                entry("脉细"), entry("脉涩"), entry("脉缓"), entry("脉滑"), entry("脉数"),
                entry("脉虚细"), entry("脉弦细"));
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(candidates);
    }

    private void recallTongue() throws IOException {
        List<TermEntry> candidates = List.of(
                entry("舌淡"), entry("舌红"), entry("舌淡红"),
                entry("薄苔", "苔薄"), entry("白腻苔", "苔白腻"),
                entry("白苔", "苔白"), entry("薄白苔", "苔薄白"),
                entry("尖红", "舌尖红"), entry("苔少", "少苔"),
                entry("齿痕舌", "齿痕", "齿印"), entry("舌红点", "红点"));
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(candidates);
    }

    private void recallSymptom() throws IOException {
        List<TermEntry> candidates = List.of(
                entry("口干"), entry("腹痛"), entry("腹胀"), entry("便溏"),
                entry("恶寒", "怕冷"), entry("汗出"), entry("流涎"), entry("喷嚏"),
                entry("夜尿频", "夜尿"), entry("咳嗽"), entry("干呕"), entry("血块"),
                entry("心悸"), entry("有痰"));
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(candidates);
    }

    private List<String> terms(String type, String input) {
        List<String> out = new ArrayList<>();
        for (EsTermNormalizer.NormalizeResult r : normalizer.decompose(type, ORG, input)) {
            assertEquals(EsTermNormalizer.LEVEL_EXACT, r.level(), "拆出的每一段都应是精确命中");
            out.add(r.standardTerm());
        }
        return out;
    }

    // ---------------------------------------------------------------- 脉象

    @Test
    void pulse_positionAndQualityAreSplit() throws IOException {
        recallPulse();
        assertEquals(List.of("左关", "脉浮"), terms("pulse", "左关偏浮"));
        assertEquals(List.of("左尺", "脉弱"), terms("pulse", "左尺稍弱"));
    }

    @Test
    void pulse_twoQualitiesAreSplit() throws IOException {
        recallPulse();
        assertEquals(List.of("脉细", "脉涩"), terms("pulse", "脉细涩"));
        assertEquals(List.of("脉弦细", "脉数"), terms("pulse", "脉弦细数"));
    }

    @Test
    void pulse_multiPositionIsSplit() throws IOException {
        recallPulse();
        assertEquals(List.of("右寸", "脉缓", "脉滑"), terms("pulse", "右寸关缓滑"));
        // 「双寸关」里的「关」不是标准词：只消费不产出，脉质照常拆
        assertEquals(List.of("脉浮", "脉沉"), terms("pulse", "双寸关略浮尺略沉"));
    }

    @Test
    void pulse_negationOrRelationIsNotDecomposed() throws IOException {
        recallPulse();
        assertTrue(terms("pulse", "脉欠流利").isEmpty(), "「欠」是否定，不能拆成脉滑");
        assertTrue(terms("pulse", "左寸沉痛").isEmpty(), "「痛」无落点，整体放弃");
    }

    @Test
    void pulse_relationWordsAreConsumedNotEmitted() throws IOException {
        recallPulse();
        // 「后/前/中/取/及/应指」只是诊法方位描述，消费掉不产出实体，脉质照常拆
        assertEquals(List.of("右寸", "脉浮"), terms("pulse", "右寸后关前浮"));
        assertEquals(List.of("脉沉"), terms("pulse", "关后略沉"));
        assertEquals(List.of("脉浮"), terms("pulse", "寸后关前略浮"));
        assertEquals(List.of("脉浮"), terms("pulse", "两寸后关前偏浮"));
        assertEquals(List.of("右尺"), terms("pulse", "右尺应指"));
        // 「左 / 右 / 下」是方位残留（脉位本身已含左右，「左略涩」= 左脉略涩）
        assertEquals(List.of("脉涩"), terms("pulse", "左略涩"));
        assertEquals(List.of("脉浮", "脉沉"), terms("pulse", "双上略浮下略沉"));
        // 回归：脉位仍然要产出（「左寸沉」不能被「左」被消费掉而丢掉「左寸」）
        assertEquals(List.of("左寸", "脉沉"), terms("pulse", "左寸沉"));
    }

    @Test
    void pulse_adverbAndConnectorAreSkipped() throws IOException {
        recallPulse();
        // 「仍 / 尚」是程度副词，「带」是连接字：都不是脉质，消费掉即可
        assertEquals(List.of("脉沉"), terms("pulse", "脉仍略沉"));
        assertEquals(List.of("脉弦"), terms("pulse", "脉尚弦"));
        assertEquals(List.of("脉弦", "脉滑"), terms("pulse", "脉弦带滑"));
    }

    @Test
    void pulse_palpationPhrasesAreSkipped() throws IOException {
        recallPulse();
        // 「不受按 / 按之 / 沉取」是切脉手法描述，不是对脉象的否定
        assertEquals(List.of("脉沉"), terms("pulse", "沉取不受按"), "「不受按」的「不」不阻断");
        assertTrue(terms("pulse", "脉欠流利").isEmpty(), "「欠」仍是否定");
    }

    @Test
    void pulse_unknownQualityIsNotDecomposed() throws IOException {
        recallPulse();
        assertTrue(terms("pulse", "左关略旺").isEmpty(),
                "「旺」在脉质单字表里，但词典没有「脉旺」这个标准词 ⇒ 无落点，整体放弃");
        assertTrue(terms("pulse", "脉弦有力").isEmpty(), "「有力」不在脉质表里，整体放弃");
    }

    // ---------------------------------------------------------------- 舌象

    @Test
    void tongue_bodyAndCoatingAreSplit() throws IOException {
        recallTongue();
        assertEquals(List.of("舌淡", "薄苔"), terms("tongue", "舌淡苔薄"));
        assertEquals(List.of("舌红", "白腻苔"), terms("tongue", "舌红苔白腻"));
        assertEquals(List.of("舌淡红", "尖红"), terms("tongue", "舌淡红尖红"));
        assertEquals(List.of("尖红"), terms("tongue", "尖红较甚"), "只剩程度修饰字时可归到单条");
    }

    @Test
    void tongue_positionalModifiersAreSkipped() throws IOException {
        recallTongue();
        // 方位 / 连接字（边 / 有 / 尖）不构成词条，跳过它们不等于丢语义
        assertEquals(List.of("齿痕舌"), terms("tongue", "边有齿痕"), "「边有齿痕」= 齿痕舌");
        assertEquals(List.of("齿痕舌"), terms("tongue", "边有齿印"));
        assertEquals(List.of("舌红点"), terms("tongue", "尖有红点"));
        assertEquals(List.of("尖红"), terms("tongue", "边尖红"));
    }

    @Test
    void tongue_unknownRemainderIsNotDecomposed() throws IOException {
        recallTongue();
        assertTrue(terms("tongue", "苔白底浮黄").isEmpty(), "「底/浮黄」无落点，整体放弃");
        assertTrue(terms("tongue", "舌淡苔薄白腻满布").isEmpty(), "「腻」无落点，不做部分拆解");
        assertTrue(terms("tongue", "齿痕较深").isEmpty(), "「较深」可跳过但「深」无落点，仍应放弃");
    }

    // ---------------------------------------------------------------- 其他类型不开放

    // ---------------------------------------------------------------- 症状

    @Test
    void symptom_contextAffixIsStripped() throws IOException {
        recallSymptom();
        assertEquals(List.of("口干"), terms("symptom", "晨起口干"));
        assertEquals(List.of("腹痛"), terms("symptom", "便前腹痛"));
        assertEquals(List.of("汗出"), terms("symptom", "夜寐汗出"));
        assertEquals(List.of("喷嚏"), terms("symptom", "晨起喷嚏较多"));
        // 触发 / 频次语境（不改变症状本体）
        assertEquals(List.of("咳嗽"), terms("symptom", "连声咳嗽"));
        assertEquals(List.of("干呕"), terms("symptom", "刷牙干呕"));
        assertEquals(List.of("流涎"), terms("symptom", "寐时流涎"));
        assertEquals(List.of("血块"), terms("symptom", "有少量血块"));
        // 程度 / 状态后缀
        assertEquals(List.of("汗出"), terms("symptom", "汗出彻全身"));
        assertEquals(List.of("汗出"), terms("symptom", "汗出一般"));
        assertEquals(List.of("口干"), terms("symptom", "有时口干"));
    }

    @Test
    void symptom_anatomicalPrefixIsStillNotStripped() throws IOException {
        recallSymptom();
        // 部位前缀仍不收（「肛门瘙痒」与「瘙痒」、「腰部冷痛」与「腰痛」的合并口径由业务定）
        assertTrue(terms("symptom", "肛门瘙痒").isEmpty());
        assertTrue(terms("symptom", "全身乏力").isEmpty(), "部位前缀不在白名单 ⇒ 不剥");
    }

    @Test
    void symptom_compositionIsSplit() throws IOException {
        recallSymptom();
        assertEquals(List.of("腹胀", "便溏"), terms("symptom", "腹胀便溏"));
    }

    @Test
    void symptom_negationOrLocationIsNotStripped() throws IOException {
        recallSymptom();
        assertTrue(terms("symptom", "无明显口干").isEmpty(), "否定语境不拆");
        assertTrue(terms("symptom", "口干已除").isEmpty(), "已愈语境不拆");
        assertTrue(terms("symptom", "腰部冷痛").isEmpty(), "位置词不在白名单，不做部分拆解");
        assertTrue(terms("symptom", "夜尿气味重").isEmpty(), "「气味重」不是可剥的程度后缀");
    }

    // ---------------------------------------------------------------- 证候

    private void recallPattern() throws IOException {
        List<TermEntry> candidates = List.of(
                entry("太阴"), entry("阳明"), entry("少阳"), entry("厥阴"),
                entry("风湿"), entry("郁热"), entry("气虚"), entry("血瘀"), entry("脾虚"));
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(candidates);
    }

    @Test
    void pattern_compositeIsSplit() throws IOException {
        recallPattern();
        assertEquals(List.of("太阴", "阳明", "风湿", "郁热"), terms("pattern", "太阴阳明风湿郁热"));
        assertEquals(List.of("阳明", "少阳"), terms("pattern", "阳明少阳"));
        assertEquals(List.of("气虚", "血瘀"), terms("pattern", "气虚血瘀"));
    }

    @Test
    void pattern_relationWordIsConsumedNotEmitted() throws IOException {
        recallPattern();
        assertEquals(List.of("阳明", "少阳"), terms("pattern", "阳明兼少阳"), "「兼」是连接字");
        assertEquals(List.of("厥阴", "太阴"), terms("pattern", "厥阴兼太阴"));
        assertEquals(List.of("少阳", "阳明", "风湿"), terms("pattern", "少阳及阳明风湿"));
    }

    @Test
    void pattern_majorSuffixIsStripped() throws IOException {
        recallPattern();
        assertEquals(List.of("厥阴"), terms("pattern", "厥阴为主"));
        assertTrue(terms("pattern", "厥阴为主已除").isEmpty(), "含「除」（已愈）不剥");
    }

    @Test
    void pattern_residualCharBlocksSplit() throws IOException {
        recallPattern();
        assertTrue(terms("pattern", "里证兼表").isEmpty(), "「表」无落点 ⇒ 整体放弃");
        assertTrue(terms("pattern", "太阴不定").isEmpty(), "「不定」无落点 ⇒ 整体放弃");
    }

    @Test
    void otherTypesAreNotDecomposed() throws IOException {
        recallPulse();
        assertTrue(terms("disease", "口腔溃疡").isEmpty(), "病名不开放拆解");
        assertTrue(terms("herb", "当归").isEmpty(), "中药不开放拆解");
    }
}
