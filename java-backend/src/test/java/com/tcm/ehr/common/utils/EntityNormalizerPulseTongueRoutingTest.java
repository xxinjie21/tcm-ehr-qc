package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #2 的守护测试：误放进 {@code symptoms} 的脉象/舌象实体必须按<b>词典命中</b>归位；
 * 而「本身就是症状术语」的必须原地不动。
 *
 * <p>实测背景（2026-10-05 直接查库）：症状字段 4170 条里有 107 条的内容恰是脉象词典里的标准词
 * （脉细数×50、左尺无力×50、脉弦劲有力×6、脉浮×1），且这些词<b>不在</b>症状词典里
 * （症状典里含「脉」的标准词为 0）—— 所以归位会全部命中它们。</p>
 *
 * <p>三条断言对应三种情形：挪走（脉）· 挪走（舌）· <b>不挪</b>（词典重叠 + 真症状）。</p>
 */
class EntityNormalizerPulseTongueRoutingTest {

    private static final String ORG = "org-A";

    /** 只有在给定词典里列出的词算命中；其余类型一律未命中 */
    private static EsTermNormalizer normalizerWith(Map<String, Set<String>> dict) {
        EsTermNormalizer n = mock(EsTermNormalizer.class);
        when(n.normalize(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            String raw = inv.getArgument(2);
            Set<String> hit = dict.get(type);
            return hit != null && hit.contains(raw)
                    ? new EsTermNormalizer.NormalizeResult(raw, 1)
                    : new EsTermNormalizer.NormalizeResult(raw, 0);
        });
        return n;
    }

    private static NlpExtractVO voWithSymptoms(String... contents) {
        NlpExtractVO vo = new NlpExtractVO();
        for (String c : contents) {
            NlpExtractVO.Entity e = new NlpExtractVO.Entity();
            e.setContent(c);
            e.setSourceText(c);
            vo.getSymptoms().add(e);
        }
        return vo;
    }

    private static List<String> contents(List<NlpExtractVO.Entity> list) {
        List<String> out = new ArrayList<>();
        for (NlpExtractVO.Entity e : list) {
            out.add(e.getContent());
        }
        return out;
    }

    private static EntityNormalizer svc(Map<String, Set<String>> dict) {
        return new EntityNormalizer(normalizerWith(dict), new ObjectMapper());
    }

    @Test
    void pulseTermInsideSymptomsIsMovedToPulseList() {
        NlpExtractVO vo = voWithSymptoms("脉细数", "头晕");
        svc(Map.of("pulse", Set.of("脉细数"), "symptom", Set.of("头晕"))).normalize(vo, ORG);

        assertEquals(List.of("脉细数"), contents(vo.getPulseList()),
                "命中脉象词典的内容必须挪进 pulseList（否则会以「未归一的症状」拉低归一率）");
        assertEquals(List.of("头晕"), contents(vo.getSymptoms()), "真症状必须留在 symptoms");
    }

    @Test
    void tongueTermInsideSymptomsIsMovedToTongueList() {
        NlpExtractVO vo = voWithSymptoms("舌红少苔", "乏力");
        svc(Map.of("tongue", Set.of("舌红少苔"), "symptom", Set.of("乏力"))).normalize(vo, ORG);

        assertEquals(List.of("舌红少苔"), contents(vo.getTongueList()));
        assertEquals(List.of("乏力"), contents(vo.getSymptoms()));
    }

    /** 词典重叠（同一内容既是症状术语又是脉象术语）时**不许挪** —— 否则会把真症状改错 */
    @Test
    void symptomDictionaryWinsWhenOverlapping() {
        NlpExtractVO vo = voWithSymptoms("脉细数");
        svc(Map.of("symptom", Set.of("脉细数"), "pulse", Set.of("脉细数"))).normalize(vo, ORG);

        assertEquals(List.of("脉细数"), contents(vo.getSymptoms()),
                "本身就是症状术语的不得挪动（词典重叠时的保护）");
        assertEquals(0, vo.getPulseList().size(), "pulseList 不该凭空多出内容");
    }

    /** #6：单字且不在词典里 → 丢弃（实测这类有 185 条，全是截断残字） */
    @Test
    void singleCharFragmentIsDropped() {
        NlpExtractVO vo = voWithSymptoms("双", "腰", "头晕");
        svc(Map.of("symptom", Set.of("头晕"))).normalize(vo, ORG);

        assertEquals(List.of("头晕"), contents(vo.getSymptoms()),
                "长度为 1 且不在词典里的必须丢弃（否则必然未归一，只会拉低归一率）");
    }

    /** #6：单字但**在词典里** → 保留（词典确有单字标准词，一刀切会误删） */
    @Test
    void singleCharDictionaryTermIsKept() {
        NlpExtractVO vo = voWithSymptoms("瘿", "头晕");
        svc(Map.of("symptom", Set.of("瘿", "头晕"))).normalize(vo, ORG);

        assertEquals(2, vo.getSymptoms().size(), "词典里的单字标准词不能被当成残词删掉");
    }
}
