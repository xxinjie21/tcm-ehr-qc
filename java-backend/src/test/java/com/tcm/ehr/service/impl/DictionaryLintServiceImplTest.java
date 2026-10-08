package com.tcm.ehr.service.impl;

import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.domain.vo.DictionaryLintVO;
import com.tcm.ehr.service.IDictionaryLintService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词表体检的回归测试（批次 21）。
 *
 *
 * 这些用例几乎全部来自**真实踩过的坑**，不是设想出来的：
 *
 *
 * 
 *   - 同名重复 —— tongues.json 里「白厚苔」重复两次，播种撞唯一键整批失败；
 *   - 原文短语当标准词 —— 早期症状词表里混着「流清涕反复发作」「双下肢水肿」；
 *   - 机械拼接别名 ——「腰部冷痛」的别名是「腰部冷疼痛」；
 *   - 别名含标准词自身 —— 疾病词表里 7 条这样；
 *   - 定义文字塞进别名 ——「慢性肾衰」的别名是「久病或反复发作之肾衰病。」。
 * 
 *
 *
 * 这些缺陷的共同点：导入不会报错，所以只能靠体检拦住。
 *
 */
class DictionaryLintServiceImplTest {

    private final IDictionaryLintService svc = new DictionaryLintServiceImpl();

    private static TermEntry term(String standard, String... aliases) {
        return new TermEntry(standard, new ArrayList<>(Arrays.asList(aliases)), "测试来源", null);
    }

    private static TermEntry coded(String standard, String code, String... aliases) {
        return new TermEntry(standard, new ArrayList<>(Arrays.asList(aliases)), "测试来源", code);
    }

    private DictionaryLintVO.Issue find(DictionaryLintVO vo, String kind) {
        return vo.getErrors().stream().filter(i -> kind.equals(i.getKind())).findFirst()
                .orElseGet(() -> vo.getWarnings().stream()
                        .filter(i -> kind.equals(i.getKind())).findFirst().orElse(null));
    }

    @Test
    @DisplayName("同名重复是硬错误（播种会撞唯一键整批失败，导入会静默少词）")
    void duplicateTermIsError() {
        DictionaryLintVO vo = svc.lint("tongue", List.of(
                term("白厚苔", "苔白厚"),
                term("白厚苔", "苔白厚")));

        assertFalse(vo.passed(), "同名重复必须判为不通过");
        DictionaryLintVO.Issue e = find(vo, "duplicate-term");
        assertTrue(e != null && "error".equals(e.getLevel()));
        assertTrue(e.getTerms().contains("白厚苔"), "问题描述要指出是哪个术语：" + e.getTerms());
        assertTrue(e.getAdvice().contains("整批失败"), "要说清后果：重复项在播种路径会整批失败");
    }

    @Test
    @DisplayName("重复项要能看到各自的别名与编码，否则无法判断该保留哪条")
    void duplicateReportsDetails() {
        DictionaryLintVO vo = svc.lint("herb", List.of(
                term("甘草", "国老"),
                coded("甘草", "GS-001", "国老草")));

        DictionaryLintVO.Issue e = find(vo, "duplicate-term");
        assertTrue(e != null);
        assertTrue(e.getTerms().contains("GS-001"),
                "重复项里带编码的那条要能看到编码，否则不知道留哪条：" + e.getTerms());
    }

    @Test
    @DisplayName("把病历原文当标准词要被指出来（早期症状词表真实存在此类问题）")
    void sentenceLikeTermIsWarned() {
        DictionaryLintVO vo = svc.lint("symptom", List.of(
                term("流清涕反复发作"),
                term("双下肢水肿"),
                term("发热")));

        DictionaryLintVO.Issue w = find(vo, "term-looks-like-sentence");
        assertTrue(w != null, "带句读的术语应被提示");
        assertTrue(w.getTerms().contains("流清涕反复发作"));
        assertTrue(w.getAdvice().contains("别名"),
                "建议要给出「标准词 + 别名」的拆法，而不只是说「有问题」");
    }

    @Test
    @DisplayName("「发作期」「持续性」这类合法术语不该被判成原文短语（标记词只在词尾才算信号）")
    void legalTermContainingMarkerIsNotFlagged() {
        // 回归：标记词原先做子串匹配，「发作」会把「发作期」判成原文短语 ——
        // 提示词（举「边有齿痕」「流清涕反复发作」为例）与被标记的词对不上，用户完全看不懂。
        DictionaryLintVO vo = svc.lint("pattern", List.of(
                term("发作期"),
                term("持续性"),
                term("发热")));

        assertNull(find(vo, "term-looks-like-sentence"),
                "标记词出现在词中间是合法术语的一部分，不能当原文短语");
    }

    @Test
    @DisplayName("单字标准词要提示核对（可能是抽取截断被粘进来）")
    void singleCharTermIsWarned() {
        DictionaryLintVO vo = svc.lint("symptom", List.of(term("双"), term("发热")));

        DictionaryLintVO.Issue w = find(vo, "term-too-short");
        assertTrue(w != null && w.getTerms().contains("双"));
    }

    @Test
    @DisplayName("过长术语要提示拆成标准词 + 别名")
    void overlongTermIsWarned() {
        // 刻意不含「反复/加重/明显」这类描述性成分，
        // 否则会被「原文短语」那条先命中，测不到长度这一项
        DictionaryLintVO vo = svc.lint("symptom",
                List.of(term("腰背部酸胀疼痛牵引至下肢麻木无力步履艰难行走不便")));

        DictionaryLintVO.Issue w = find(vo, "term-too-long");
        assertTrue(w != null, "超过 20 字且不含描述性成分的术语应命中「过长」");
        assertTrue(w.getAdvice().contains("别名"));
    }

    @Test
    @DisplayName("过长且带描述成分的，归到「原文短语」而不是「过长」（提示更贴切）")
    void overlongPhraseIsReportedAsPhrase() {
        DictionaryLintVO vo = svc.lint("symptom",
                List.of(term("患者自述近两日来腰背部持续性酸胀疼痛明显加重")));

        assertTrue(find(vo, "term-looks-like-sentence") != null);
    }

    @Test
    @DisplayName("别名与标准词相同要提示（归一会自己命中自己，掩盖真实匹配）")
    void selfAliasIsWarned() {
        DictionaryLintVO vo = svc.lint("disease", List.of(term("慢性胃炎", "慢性胃炎")));

        DictionaryLintVO.Issue w = find(vo, "self-alias");
        assertTrue(w != null && w.getTerms().contains("慢性胃炎"));
    }

    @Test
    @DisplayName("编码重复比格式错更危险（两个术语抢同一个码）")
    void duplicateCodeIsWarned() {
        DictionaryLintVO vo = svc.lint("herb", List.of(
                coded("甘草", "GS-001"),
                coded("炙甘草", "GS-001")));

        DictionaryLintVO.Issue w = find(vo, "code-duplicate");
        assertTrue(w != null, "重复编码要提示");
        assertTrue(w.getTerms().contains("GS-001"));
        assertTrue(w.getAdvice().contains("一对一"), "要说清编码必须一对一");
    }

    @Test
    @DisplayName("编码填了非编码内容要提示（多半是把来源写进了编码列）")
    void badCodeShapeIsWarned() {
        DictionaryLintVO vo = svc.lint("herb", List.of(
                coded("甘草", "《中国药典》2025年版 甘草项下")));

        DictionaryLintVO.Issue w = find(vo, "code-shape");
        assertTrue(w != null);
        assertTrue(w.getAdvice().contains("来源列"), "建议要指明正确位置");
    }

    @Test
    @DisplayName("正常词表不报噪声（体检不能变成挑刺）")
    void cleanDictionaryPassesQuietly() {
        DictionaryLintVO vo = svc.lint("pattern", List.of(
                term("肝胃不和证", "肝胃不和"),
                coded("脾肾阳虚证", "BNF02001"),
                term("肝郁气滞", "肝气郁结")));

        assertTrue(vo.passed(), "正常词表应通过：" + vo.getErrors());
        assertTrue(vo.getWarnings().isEmpty(),
                "正常词表不该有警告，实际：" + vo.getWarnings());
    }

    @Test
    @DisplayName("空词表要给出明确原因而不是空结果")
    void emptyDictionaryIsExplained() {
        DictionaryLintVO vo = svc.lint("symptom", List.of());

        assertFalse(vo.passed());
        assertTrue(vo.getErrors().stream().anyMatch(i -> "empty".equals(i.getKind())));
        assertEquals(0, vo.getTotal());
    }

    @Test
    @DisplayName("成片出现的问题要排在最前（同一种毛病改一处能消掉一大片）")
    void frequentIssuesRankFirst() {
        List<TermEntry> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(term("舌质淡" + i, "舌质淡" + i));   // 8 条自别名
        }
        many.add(term("脉弦劲有力", "脉弦劲有力"));        // 1 条重复
        many.add(term("脉弦劲有力", "脉弦劲有力"));
        DictionaryLintVO vo = svc.lint("pulse", many);

        assertFalse(vo.getTopIssues().isEmpty());
        assertEquals("self-alias", vo.getTopIssues().get(0).getKind(),
                "覆盖面最广的问题应排第一，实际首项：" + vo.getTopIssues().get(0).getKind());
    }
}