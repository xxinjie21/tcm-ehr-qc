package com.tcm.ehr.common.utils;

import com.tcm.ehr.domain.vo.NlpExtractVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证候回补的契约。
 *
 * <p>背景（实测数据）：{@code pattern} 列 1000/1000 有文本，但 {@code patternList}
 * 只有 800 条抽出 —— 证候<b>只有模型一条来源</b>（{@code python-nlp} 的规则兜底不含
 * patternList），模型漏抽就彻底空。而中医诊断列有疾病回补、处方列有中药回补，
 * 辨证结论列此前没有对应回补，这是缺口。</p>
 *
 * <p>回补必须走 {@code scan}（查词典）而不是按顿号裸切分：裸切分会把「经络不通」
 * 这类非标准词原样塞进 patternList，制造未归一项。</p>
 */
class EntityNormalizerPatternBackfillTest {

    private static final String ORG = "org-A";
    private static final String PATTERN_TEXT = "肝阳上亢，肝火上炎，上扰清窍";

    /** 回补后 normalize 会逐实体归一；统一给一个 level=1 的结果，避免桩返回 null 触发 NPE */
    private static EsTermNormalizer mockNormalizer(EsTermNormalizer n) {
        when(n.normalize(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new EsTermNormalizer.NormalizeResult(
                        inv.getArgument(2), "中医病证分类与代码", 1, null));
        return n;
    }

    @Test
    @DisplayName("模型漏抽证候时，从辨证结论列按词典回补出来")
    void backfillPatternsRecoversMissedTerms() {
        EsTermNormalizer normalizer = mockNormalizer(mock(EsTermNormalizer.class));
        when(normalizer.scan(eq("pattern"), eq(ORG), eq(PATTERN_TEXT)))
                .thenReturn(new LinkedHashSet<>(List.of("肝阳上亢", "肝火上炎")));

        EntityNormalizer svc = new EntityNormalizer(normalizer);
        NlpExtractVO vo = NlpExtractVO.empty();
        // 模型漏抽：patternList 为空，但 pattern 列有文本
        assertTrue(vo.getPatternList().isEmpty());

        svc.backfillFromRaw(vo, ORG, null, null, PATTERN_TEXT);

        List<String> got = new ArrayList<>();
        for (NlpExtractVO.Entity e : vo.getPatternList()) {
            got.add(e.getContent());
        }
        assertTrue(got.contains("肝阳上亢"), "应回补出 肝阳上亢，实际: " + got);
        assertTrue(got.contains("肝火上炎"), "应回补出 肝火上炎，实际: " + got);
    }

    @Test
    @DisplayName("辨证结论列为空：完全不查词典（不给每次批量解析白加一次 ES 查询）")
    void blankPatternColumnSkipsScan() {
        EsTermNormalizer normalizer = mockNormalizer(mock(EsTermNormalizer.class));
        EntityNormalizer svc = new EntityNormalizer(normalizer);

        svc.backfillFromRaw(NlpExtractVO.empty(), ORG, null, null, "   ");

        verify(normalizer, never()).scan(eq("pattern"), anyString(), anyString());
    }

    @Test
    @DisplayName("证候已全部归一：不触发回补（避免重复 append）")
    void fullyNormalizedPatternsSkipBackfill() {
        EsTermNormalizer normalizer = mockNormalizer(mock(EsTermNormalizer.class));
        EntityNormalizer svc = new EntityNormalizer(normalizer);

        NlpExtractVO vo = NlpExtractVO.empty();
        NlpExtractVO.Entity e = new NlpExtractVO.Entity();
        e.setContent("肝阳上亢");
        e.setSourceText("肝阳上亢");
        e.setNormLevel(1);
        vo.getPatternList().add(e);

        svc.backfillFromRaw(vo, ORG, null, null, PATTERN_TEXT);

        verify(normalizer, never()).scan(eq("pattern"), anyString(), anyString());
    }

    @Test
    @DisplayName("回补不会碰别的字段：只有证候列被扫")
    void onlyPatternColumnIsScanned() {
        EsTermNormalizer normalizer = mockNormalizer(mock(EsTermNormalizer.class));
        when(normalizer.scan(anyString(), anyString(), anyString()))
                .thenReturn(new LinkedHashSet<>(List.of("肝阳上亢")));
        EntityNormalizer svc = new EntityNormalizer(normalizer);

        svc.backfillFromRaw(NlpExtractVO.empty(), ORG, null, null, PATTERN_TEXT);

        verify(normalizer, never()).scan(eq("herb"), any(), any());
        verify(normalizer, never()).scan(eq("disease"), any(), any());
    }
}
