package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.NlpExtractVO;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 归一链路的清理与原文口径（2026-10-19）。
 *
 * <p>两件事：① 噪声碎片（单维描述 / 裸部位 / 残词）在归一失败时整条丢弃；
 * ② 归一以 {@code sourceText}（抽取原文）为准，而不是 {@code content} —— 后者可能是上一轮归一
 * 写回的标准词，拿它再归一等于自命中。</p>
 */
class EntityNormalizerCleanupTest {

    private static final String ORG = "org-A";

    private EsTermNormalizer termNormalizer;

    @BeforeEach
    void setUp() throws IOException {
        IEsTermIndexService es = Mockito.mock(IEsTermIndexService.class);
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            if ("pulse".equals(type)) {
                return List.of(entry("左关"), entry("脉浮"), entry("脉细"), entry("脉涩"), entry("脉沉"),
                        entry("脉软", "软"));
            }
            if ("symptom".equals(type)) {
                return List.of(entry("口干"), entry("腹胀"), entry("便溏"));
            }
            return List.of();
        });
        termNormalizer = new EsTermNormalizer(es);
        ReflectionTestUtils.setField(termNormalizer, "scoreThreshold", 0.8);
        ReflectionTestUtils.setField(termNormalizer, "containDelta", 1);
    }

    private static TermEntry entry(String standardTerm, String... aliases) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(standardTerm);
        e.setAliases(List.of(aliases));
        return e;
    }

    private static NlpExtractVO.Entity entity(String content, String sourceText) {
        NlpExtractVO.Entity e = new NlpExtractVO.Entity();
        e.setContent(content);
        e.setSourceText(sourceText);
        e.setSource("model");
        return e;
    }

    private EntityNormalizer normalizer() {
        return new EntityNormalizer(termNormalizer, new ObjectMapper());
    }

    private static List<String> contents(List<NlpExtractVO.Entity> list) {
        List<String> out = new ArrayList<>();
        for (NlpExtractVO.Entity e : list) {
            out.add(e.getContent());
        }
        return out;
    }

    @Test
    void noiseFragmentsAreDropped() {
        NlpExtractVO vo = new NlpExtractVO();
        for (String s : new String[]{"成形", "大便", "色黄", "晨起", "偏干", "量少", "口气", "慢性"}) {
            vo.getSymptoms().add(entity(s, s));
        }
        vo.getSymptoms().add(entity("口干", "口干"));

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("口干"), contents(vo.getSymptoms()), "碎片丢弃、真症状保留");
    }

    @Test
    void noiseFilterNeverDropsNormalizedEntities() {
        // 「量」这类形状上像碎片、但确实是词典标准词的，命中后必须保留
        NlpExtractVO vo = new NlpExtractVO();
        vo.getSymptoms().add(entity("口干", "口干"));
        vo.getSymptoms().add(entity("大便", "大便"));

        normalizer().normalize(vo, ORG);

        assertTrue(contents(vo.getSymptoms()).contains("口干"));
    }

    @Test
    void normalizationUsesSourceTextNotContent() {
        NlpExtractVO vo = new NlpExtractVO();
        // content 是上一轮归一写回的标准词，sourceText 才是原文短语
        vo.getPulseList().add(entity("脉细", "左关偏浮"));

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("左关", "脉浮"), contents(vo.getPulseList()),
                "以 sourceText 重新归一，才能从原短语里拆出全部部件（否则自命中成「脉细」一条）");
    }

    @Test
    void decomposablePulseJargonInSymptomsIsMovedToPulse() {
        NlpExtractVO vo = new NlpExtractVO();
        // 抽取侧把脉象内容打进症状：整串命中不了脉象词典，但能被拆解规则完整拆开
        vo.getSymptoms().add(entity("左略涩", "左略涩"));
        vo.getSymptoms().add(entity("双上略浮下略沉", "双上略浮下略沉"));
        vo.getSymptoms().add(entity("口干", "口干"));

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("口干"), contents(vo.getSymptoms()), "真症状留下");
        assertEquals(List.of("脉涩", "脉浮", "脉沉"), contents(vo.getPulseList()),
                "挪到脉象后按拆解结果落标准词");
    }

    @Test
    void staleMappingIsRecomputedFromSourceText() {
        NlpExtractVO vo = new NlpExtractVO();
        // 上一轮归一写回的样子：content=标准词、normLevel=1、sourceText=原文
        NlpExtractVO.Entity stale = entity("脉细", "左关偏浮");
        stale.setNormLevel(EsTermNormalizer.LEVEL_EXACT);
        vo.getPulseList().add(stale);

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("左关", "脉浮"), contents(vo.getPulseList()),
                "旧映射必须按原文重算，而不是原样保留");
    }

    @Test
    void staleMappingIsDroppedWhenNoLongerHittable() {
        NlpExtractVO vo = new NlpExtractVO();
        // 上一轮经单字别名「软」误映射成「脉软」（R8 已撤回该路径）
        NlpExtractVO.Entity stale = entity("脉软", "质软");
        stale.setNormLevel(EsTermNormalizer.LEVEL_CONTAIN);
        vo.getPulseList().add(stale);

        normalizer().normalize(vo, ORG);

        assertEquals(1, vo.getPulseList().size());
        assertEquals("质软", contents(vo.getPulseList()).get(0), "content 回到原文");
        assertNull(vo.getPulseList().get(0).getNormLevel(), "旧 normLevel 必须清掉");
    }

    @Test
    void unhittableJargonIsNotMoved() {
        NlpExtractVO vo = new NlpExtractVO();
        // 这两条既不是脉象内容、也拆不出脉象词条 —— 宁可留着，也不能凭猜改类型
        vo.getSymptoms().add(entity("大便日一行", "大便日一行"));
        vo.getSymptoms().add(entity("活动后缓解", "活动后缓解"));

        normalizer().normalize(vo, ORG);

        assertTrue(vo.getPulseList().isEmpty(), "拆不出词条的一律不挪");
        assertEquals(List.of("大便日一行", "活动后缓解"), contents(vo.getSymptoms()));
    }
}
