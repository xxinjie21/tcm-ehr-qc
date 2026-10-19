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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 拆解在归一链路上的接线：未命中 / 包含命中都要拆，拆出的实体都要写 normLevel。 */
class EntityNormalizerDecomposeTest {

    private static final String ORG = "org-A";

    private EsTermNormalizer termNormalizer;

    @BeforeEach
    void setUp() throws IOException {
        IEsTermIndexService es = Mockito.mock(IEsTermIndexService.class);
        when(es.search(anyString(), anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            if ("pulse".equals(type)) {
                return List.of(entry("左关"), entry("脉浮"), entry("脉细"), entry("脉涩"));
            }
            if ("tongue".equals(type)) {
                return List.of(entry("舌淡"), entry("薄苔", "苔薄"));
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

    private static NlpExtractVO.Entity entity(String content) {
        NlpExtractVO.Entity e = new NlpExtractVO.Entity();
        e.setContent(content);
        e.setSourceText(content);
        e.setSource("rule");
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
    void missIsSplitIntoExactTerms() {
        NlpExtractVO vo = new NlpExtractVO();
        vo.getPulseList().add(entity("左关偏浮"));
        vo.getTongueList().add(entity("舌淡苔薄"));

        EntityNormalizer.NormStat stat = normalizer().normalize(vo, ORG);

        assertEquals(List.of("左关", "脉浮"), contents(vo.getPulseList()));
        assertEquals(List.of("舌淡", "薄苔"), contents(vo.getTongueList()));
        assertEquals(4, stat.hit(), "拆出的每一段都算一次归一");
        assertEquals(4, stat.exact());
        for (NlpExtractVO.Entity e : vo.getPulseList()) {
            assertEquals("左关偏浮", e.getSourceText(), "拆出的实体保留原短语作为 sourceText");
        }
    }

    @Test
    void containHitIsAlsoSplit() {
        NlpExtractVO vo = new NlpExtractVO();
        vo.getPulseList().add(entity("脉细涩"));

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("脉细", "脉涩"), contents(vo.getPulseList()),
                "「脉细涩」按差额 1 只会归到「脉细」，拆解要保留「涩」");
    }

    @Test
    void symptomAffixAndCompositionAreSplit() {
        NlpExtractVO vo = new NlpExtractVO();
        vo.getSymptoms().add(entity("晨起口干"));
        vo.getSymptoms().add(entity("腹胀便溏"));

        normalizer().normalize(vo, ORG);

        assertEquals(List.of("口干", "腹胀", "便溏"), contents(vo.getSymptoms()));
    }

    @Test
    void nonCompositeTypesAreNotSplit() {
        NlpExtractVO vo = new NlpExtractVO();
        vo.getPatternList().add(entity("里证兼表"));

        EntityNormalizer.NormStat stat = normalizer().normalize(vo, ORG);

        assertEquals(List.of("里证兼表"), contents(vo.getPatternList()));
        assertEquals(0, stat.hit(), "证候不开放组合拆解");
    }
}
