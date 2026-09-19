package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 归一器改造（内存三级匹配 → ES 召回 + Java 判定）的行为等价性测试。
 *
 * <p>核心要证明的是<b>行为不变</b>：ES 召回只是加速通道，无论 ES 返回什么（正常候选 / 空 / 抛异常），
 * 最终归一结果都必须与「纯内存全量匹配」一致——否则归一结果会随 ES 状态漂移。</p>
 */
class EsTermNormalizerTest {

    private static final String TYPE = "symptom";
    /** 词典：标准词「咽痛」，别名「咽喉痛」 */
    private static final String STD = "咽痛";
    private static final String ALIAS = "咽喉痛";

    private IEsTermIndexService es;
    private DictionaryStore store;
    private EsTermNormalizer normalizer;

    @BeforeEach
    void setUp() {
        es = Mockito.mock(IEsTermIndexService.class);
        store = new DictionaryStore();
        store.put(TYPE, List.of(entry(STD, List.of(ALIAS), "中医临床诊疗术语 症状")));

        normalizer = new EsTermNormalizer(store, es);
        ReflectionTestUtils.setField(normalizer, "scoreThreshold", 0.8);
    }

    private static TermEntry entry(String standardTerm, List<String> aliases, String source) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(standardTerm);
        e.setAliases(aliases);
        e.setSource(source);
        return e;
    }

    // ---------------------------------------------------------------- ES 命中

    /** ES 正常召回：别名精确命中，命中路径只比较候选集 */
    @Test
    void esRecall_shouldDecideWithinCandidates() throws IOException {
        when(es.search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(entry(STD, List.of(ALIAS), "中医临床诊疗术语 症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, ALIAS);

        assertEquals(STD, r.standardTerm(), "别名应归一为标准词");
        assertEquals("中医临床诊疗术语 症状", r.source());
    }

    // ---------------------------------------------------------------- 兜底与降级

    /** ES 返回空（索引缺失 / 未召回）：必须由内存兜底命中，结果不变 */
    @Test
    void esEmptyRecall_shouldFallBackToMemory() throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertEquals(STD, normalizer.normalize(TYPE, ALIAS).standardTerm(),
                "ES 未召回时必须回退内存，否则归一结果会漏");
    }

    /** ES 抛异常（服务不可用）：降级到内存，不得外抛 */
    @Test
    void esThrowing_shouldFallBackToMemoryQuietly() throws IOException {
        when(es.search(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("connection refused"));

        assertEquals(STD, normalizer.normalize(TYPE, ALIAS).standardTerm(),
                "ES 不可用时必须静默降级到内存");
    }

    /** ES 召回了候选但判定都不中：仍要回退内存全量，避免「候选不全」导致漏判 */
    @Test
    void esCandidatesMiss_shouldStillConsultMemory() throws IOException {
        when(es.search(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(entry("头痛", List.of(), "症状")));

        assertEquals(STD, normalizer.normalize(TYPE, ALIAS).standardTerm(),
                "候选集判定未中时必须再查内存全量");
    }

    // ---------------------------------------------------------------- 三级判定阈值

    /** 二级包含：输入包含标准词 */
    @Test
    void containsLevel_shouldHit() throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertEquals(STD, normalizer.normalize(TYPE, "咽痛伴发热").standardTerm());
    }

    /** 三级 Dice：咽喉痛 vs 咽痛 = 2*2/(3+2) = 0.8，恰好达阈值应命中 */
    @Test
    void diceLevel_shouldHitAtThreshold() throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        DictionaryStore s = new DictionaryStore();
        s.put(TYPE, List.of(entry(STD, List.of(), "症状")));
        EsTermNormalizer n = new EsTermNormalizer(s, es);
        ReflectionTestUtils.setField(n, "scoreThreshold", 0.8);

        assertEquals(STD, n.normalize(TYPE, "咽喉痛").standardTerm(),
                "Dice=0.8 恰好等于阈值，应命中");
    }

    /** 三级 Dice：腰部冷痛 vs 咽痛 = 2*1/(4+2) ≈ 0.33 远低于阈值，应拒绝 */
    @Test
    void diceLevel_shouldRejectBelowThreshold() throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "腰部冷痛");

        assertEquals("腰部冷痛", r.standardTerm(), "相似度不足时应返回原词");
        assertEquals("", r.source(), "未命中时 source 应置空");
    }

    // ---------------------------------------------------------------- 边界

    /** 未命中：返回原词、source 置空 */
    @Test
    void noHit_shouldReturnInputWithEmptySource() throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "完全无关的词");

        assertEquals("完全无关的词", r.standardTerm());
        assertEquals("", r.source());
    }

    /** 空输入：原样返回，且不应触发检索 */
    @Test
    void blankInput_shouldReturnAsIsWithoutSearch() throws IOException {
        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "   ");

        assertEquals("   ", r.standardTerm());
        assertEquals("", r.source());
        Mockito.verify(es, Mockito.never()).search(anyString(), anyString(), anyInt());
    }
}
