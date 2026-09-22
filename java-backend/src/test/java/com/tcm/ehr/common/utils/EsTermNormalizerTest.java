package com.tcm.ehr.common.utils;

import com.tcm.ehr.common.exception.TermIndexUnavailableException;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 归一器的行为测试。**2026-09-23 起 ES 是唯一权威**：内存兜底已删除，
 * 所以这里不再有「ES 漏召回 → 内存接住」的用例，取而代之的是
 * 「ES 漏召回 → 就是未命中」「ES 抛异常 → 抛 TermIndexUnavailableException」。
 *
 * <p>夹具不再用 DictionaryStore（已删除），改为直接桩住 ES 的 {@code search} 返回值 ——
 * 这正是「召回结果喂给三级判定」这条真实路径，比原先更贴近生产。</p>
 */
class EsTermNormalizerTest {

    private static final String TYPE = "symptom";
    /** 词典：标准词「咽痛」，别名「咽喉痛」 */
    private static final String STD = "咽痛";
    private static final String ALIAS = "咽喉痛";

    private IEsTermIndexService es;
    private EsTermNormalizer normalizer;

    @BeforeEach
    void setUp() {
        es = Mockito.mock(IEsTermIndexService.class);
        normalizer = new EsTermNormalizer(es);
        ReflectionTestUtils.setField(normalizer, "scoreThreshold", 0.8);
    }

    private static TermEntry entry(String standardTerm, List<String> aliases, String source) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(standardTerm);
        e.setAliases(aliases);
        e.setSource(source);
        return e;
    }

    /** 桩：ES 召回返回给定候选 */
    private void esRecalls(List<TermEntry> candidates) throws IOException {
        when(es.search(anyString(), anyString(), anyInt())).thenReturn(candidates);
    }

    /** ES 正常召回：别名精确命中，判定只比较候选集 */
    @Test
    void esHit_shouldDecideWithinCandidates() throws IOException {
        esRecalls(List.of(entry(STD, List.of(ALIAS), "中医临床诊疗术语 症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, ALIAS);

        assertEquals(STD, r.standardTerm(), "别名应归一为标准词");
        assertEquals("中医临床诊疗术语 症状", r.source());
        assertEquals(1, r.level(), "别名完全相等属精确命中");
    }

    // ---------------------------------------------------------------- ES 是唯一权威

    /**
     * ES 返回空（索引缺失 / 未召回）：**就是未命中，不再有内存兜底**。
     *
     * <p>改造前这里会回退内存全量、照样命中；现在必须如实返回未命中 ——
     * 这正是「ES 的角色可判定」的代价与目的。</p>
     */
    @Test
    void esEmptyRecall_shouldBeNoHit() throws IOException {
        esRecalls(List.of());

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, ALIAS);

        assertEquals(ALIAS, r.standardTerm(), "未召回时按原文返回，不得再从别处找补");
        assertEquals("", r.source());
        assertEquals(0, r.level());
    }

    /** ES 召回了候选但三级都不中：**同样是未命中**，不得再去别处全量比对 */
    @Test
    void esCandidatesMiss_shouldBeNoHit() throws IOException {
        esRecalls(List.of(entry("头痛", List.of(), "症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, ALIAS);

        assertEquals(ALIAS, r.standardTerm());
        assertEquals(0, r.level());
    }

    /**
     * ES 抛异常（服务不可用）：**必须抛出，不得静默降级**。
     *
     * <p>若这里吞掉异常返回未命中，页面上会显示成「词典里没收录这个词」——
     * 把服务故障说成词典缺词，用户会去做完全错误的下一步。</p>
     */
    @Test
    void esThrowing_shouldPropagateAsUnavailable() throws IOException {
        when(es.search(anyString(), anyString(), anyInt()))
                .thenThrow(new IOException("connection refused"));

        assertThrows(TermIndexUnavailableException.class,
                () -> normalizer.normalize(TYPE, ALIAS),
                "ES 不可用必须显式抛出，由上层映射成 503");
    }

    /**
     * ES 连不上时抛的是 <b>unchecked</b> 的 ElasticsearchException，不是 IOException。
     *
     * <p>这条用例是实测补的：原先 {@code recall} 只 catch IOException，漏掉了 unchecked 的
     * ElasticsearchException，于是它冒到兜底处理器变成 500「系统异常」—— 用户看到的是
     * 「系统异常」而不是「术语索引不可用」，且 HTTP 码是 500 不是 503。任何索引查询失败
     * 在语义上都等于索引不可用，必须一并包装。</p>
     */
    @Test
    void esThrowingUnchecked_shouldAlsoPropagateAsUnavailable() throws IOException {
        when(es.search(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("ElasticsearchException: ConnectException: Connection refused"));

        assertThrows(TermIndexUnavailableException.class,
                () -> normalizer.normalize(TYPE, ALIAS),
                "unchecked 的 ES 异常同样必须包装成「索引不可用」");
    }

    // ---------------------------------------------------------------- 三级判定阈值

    /** 二级包含：输入包含标准词 */
    @Test
    void containsLevel_shouldHit() throws IOException {
        esRecalls(List.of(entry(STD, List.of(ALIAS), "症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "咽痛伴发热");

        assertEquals(STD, r.standardTerm());
        assertEquals(2, r.level(), "输入包含标准词属包含命中");
    }

    /** 三级 Dice：咽喉痛 vs 咽痛 = 2*2/(3+2) = 0.8，恰好达阈值应命中 */
    @Test
    void diceLevel_shouldHitAtThreshold() throws IOException {
        esRecalls(List.of(entry(STD, List.of(), "症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "咽喉痛");

        assertEquals(STD, r.standardTerm(), "Dice=0.8 恰好等于阈值，应命中");
        assertEquals(3, r.level());
    }

    /** 三级 Dice：腰部冷痛 vs 咽痛 = 2*1/(4+2) ≈ 0.33 远低于阈值，应拒绝 */
    @Test
    void diceLevel_shouldRejectBelowThreshold() throws IOException {
        esRecalls(List.of(entry(STD, List.of(), "症状")));

        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "腰部冷痛");

        assertEquals("腰部冷痛", r.standardTerm(), "相似度不足时应返回原词");
        assertEquals("", r.source(), "未命中时 source 应置空");
    }

    // ---------------------------------------------------------------- 边界

    /** 空输入：原样返回，且不应触发检索 */
    @Test
    void blankInput_shouldReturnAsIsWithoutSearch() throws IOException {
        EsTermNormalizer.NormalizeResult r = normalizer.normalize(TYPE, "   ");

        assertEquals("   ", r.standardTerm());
        assertEquals("", r.source());
        Mockito.verify(es, Mockito.never()).search(anyString(), anyString(), anyInt());
    }
}
