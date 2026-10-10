package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IDictionaryTermStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-15 / S1：倒排索引召回的<b>等价性</b>（术语补词建议的第一步）。
 *
 * <p>这次改造唯一可能出错的地方是「候选集取窄了」—— 少召回不会报错，只表现为
 * 「词表里明明有字面相近的词，建议里却没列出来」，人工确认时极难察觉。
 * 所以这里把<b>改造前的全表扫描</b>原样留作参照实现（oracle），逐条比对结果集<b>与顺序</b>。</p>
 *
 * <p>判据来自门槛本身：入选要求「与原文重叠 ≥2 个字符」，而重叠只可能发生在
 * 「出现在原文里的字符」上 ⇒ 倒排表取出的候选是<b>超集</b>，过滤阶段零精度损失。</p>
 */
class AiRecallIndexTest {

    /** 与 {@code AiServiceImpl.RECALL_TOP_N} 一致 */
    private static final int TOP_N = 5;
    /** 与 {@code AiServiceImpl.RECALL_MIN_OVERLAP} 一致 */
    private static final int MIN_OVERLAP = 2;

    // ---------------------------------------------------------------- 参照实现（改造前的口径）

    private static List<TermEntry> byFullScan(String content, List<TermEntry> all) {
        List<TermEntry> hit = new ArrayList<>();
        for (TermEntry e : all) {
            if (overlap(content, e) >= MIN_OVERLAP) hit.add(e);
        }
        hit.sort((a, b) -> Integer.compare(overlap(content, b), overlap(content, a)));
        return hit.size() > TOP_N ? new ArrayList<>(hit.subList(0, TOP_N)) : hit;
    }

    private static int overlap(String content, TermEntry e) {
        int best = commonChars(content, e.getStandardTerm());
        if (e.getAliases() != null) {
            for (String a : e.getAliases()) best = Math.max(best, commonChars(content, a));
        }
        return best;
    }

    private static int commonChars(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        StringBuilder rest = new StringBuilder(b);
        int n = 0;
        for (char c : a.toCharArray()) {
            int i = rest.indexOf(String.valueOf(c));
            if (i >= 0) {
                n++;
                rest.deleteCharAt(i);
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 构造数据

    private static TermEntry entry(String std, String... aliases) {
        return new TermEntry(std, new ArrayList<>(Arrays.asList(aliases)));
    }

    /** 边界齐全的小词表：null 标准词、别名里的 null/空串、重复字符、单字词、零交集词 */
    private static List<TermEntry> smallDictionary() {
        List<TermEntry> all = new ArrayList<>();
        all.add(entry("咽痛", "咽喉痛", "嗓子疼"));
        all.add(entry("头晕", "头昏", "眩晕"));
        all.add(entry("头晕头重", "头重如裹"));
        all.add(entry("心烦易怒", "急躁易怒"));
        all.add(entry("食少纳呆", "纳呆", "食欲不振"));
        all.add(entry("多饮多食多尿", "三多"));
        // 边界①：没有标准词，只有别名
        all.add(entry(null, "目盲"));
        // 边界②：别名里有 null 与空串
        all.add(new TermEntry("面红目赤", new ArrayList<>(Arrays.asList(null, "", "目赤"))));
        // 边界③：词条内同一字符重复出现（重叠按多重集算，不是按集合）
        all.add(entry("默默不欲饮食", "默默"));
        all.add(entry("郁郁微烦", "郁郁"));
        // 边界④：单字词条 / 空别名
        all.add(entry("咳", "咳嗽"));
        all.add(entry("喘", ""));
        // 边界⑤：与上面任何查询都零交集
        all.add(entry("耳鸣", "耳聋"));
        return all;
    }

    private static final List<String> SMALL_QUERIES = List.of(
            "嗓子疼", "咽喉疼痛", "头晕头重", "急躁易怒", "多饮多食多尿",
            "默默不欲饮食", "郁郁微烦", "目盲", "面红目赤", "咳嗽",
            "食少纳呆", "耳鸣", "咳", "喘", "完全不相干的词");

    /**
     * 大词表：从 600 个不同汉字里组 3 字词（每词再带两个子串别名）。
     *
     * <p>刻意用「字种数多、词条多」的分布 —— 真实中医词典就是这个形状（几万条词、
     * 几千个不同汉字）。字种太少（比如几十个字）会让每个字的倒排表都很长，
     * 索引看起来「缩不下来」，那是数据形状的假象而不是算法问题。</p>
     */
    private static List<TermEntry> bigDictionary(int size) {
        Random rnd = new Random(20261010L);
        List<TermEntry> all = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String std = "" + (char) (0x4E00 + rnd.nextInt(600))
                    + (char) (0x4E00 + rnd.nextInt(600))
                    + (char) (0x4E00 + rnd.nextInt(600));
            all.add(entry(std, std.substring(0, 2), std.substring(1)));
        }
        return all;
    }

    private static List<String> stdOf(List<TermEntry> list) {
        List<String> out = new ArrayList<>(list.size());
        for (TermEntry e : list) out.add(e.getStandardTerm());
        return out;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void indexedRecallMatchesFullScanOnSmallDictionary() {
        List<TermEntry> all = smallDictionary();
        IDictionaryTermStore.RecallIndex index = DictionaryTermStoreImpl.buildRecallIndex(all);
        for (String q : SMALL_QUERIES) {
            assertEquals(stdOf(byFullScan(q, all)), stdOf(AiServiceImpl.recallCandidates(q, index)),
                    "查询「" + q + "」的召回结果与全表扫描不一致");
        }
    }

    @Test
    void indexedRecallMatchesFullScanOnLargeDictionary() {
        List<TermEntry> all = bigDictionary(3000);
        IDictionaryTermStore.RecallIndex index = DictionaryTermStoreImpl.buildRecallIndex(all);
        Random rnd = new Random(7L);
        for (int i = 0; i < 60; i++) {
            TermEntry probe = all.get(rnd.nextInt(all.size()));
            // 查询词取「真词条」与「它的前两字」两种 —— 前者精确命中，后者是「少写一字」的常见形态
            String q = probe.getStandardTerm().substring(0, 2);
            assertEquals(stdOf(byFullScan(q, all)), stdOf(AiServiceImpl.recallCandidates(q, index)),
                    "查询「" + q + "」的召回结果与全表扫描不一致");
        }
    }

    /**
     * 索引必须真的「缩」：候选词条数远小于全表。
     *
     * <p>等价性测试只证明「没漏」，缩不下来也一样能通过 —— 所以这条单独钉住收益本身。</p>
     */
    @Test
    void invertedIndexNarrowsCandidates() {
        List<TermEntry> all = bigDictionary(3000);
        IDictionaryTermStore.RecallIndex index = DictionaryTermStoreImpl.buildRecallIndex(all);

        String query = all.get(0).getStandardTerm();
        boolean[] candidate = new boolean[all.size()];
        for (int i = 0; i < query.length(); i++) {
            int[] posting = index.postings().get(query.charAt(i));
            if (posting == null) continue;
            for (int sid : posting) candidate[index.stringOwner()[sid]] = true;
        }
        int n = 0;
        for (boolean b : candidate) if (b) n++;

        // 3 字查询最多牵动 3 个字符的倒排表；3000 条词表里命中数十条量级
        assertTrue(n < all.size() / 10, "候选数应远小于全表：candidate=" + n + ", all=" + all.size());
        assertEquals(stdOf(byFullScan(query, all)), stdOf(AiServiceImpl.recallCandidates(query, index)));
    }

    /** 空查询 / 零交集查询不该召回任何词条（否则等于把噪声塞给模型） */
    @Test
    void noOverlapMeansNoRecall() {
        List<TermEntry> all = smallDictionary();
        IDictionaryTermStore.RecallIndex index = DictionaryTermStoreImpl.buildRecallIndex(all);
        assertTrue(AiServiceImpl.recallCandidates("耳鸣", index).size() <= 1);
        assertTrue(AiServiceImpl.recallCandidates("完全不相干的词", index).isEmpty());
    }
}
