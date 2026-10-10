package com.tcm.ehr.service.impl;

import com.tcm.ehr.common.utils.DistLock;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IDictionaryTermStore;
import com.tcm.ehr.service.IEsTermIndexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词典页搜索改用倒排表取候选（性能审查 P2-9）的等价性守卫。
 *
 * <p><b>为什么要测</b>：改造把「对每一条词条跑 {@code contains}」换成「先用字符倒排表取候选超集、
 * 再在候选上跑同一份 {@code contains}」。这不是近似召回 —— 判据没变，只是先缩候选。但一旦
 * 交集算法或「串编号 → 词条下标」的映射写错，就会出现<b>漏命中或乱序</b>：搜索页少几条、翻页错位，
 * 而单元测试之外没人会立刻发现。这里用「与全表扫描逐条比对」把它钉死，包括</p>
 * <ul>
 *   <li>关键字只出现在别名里；</li>
 *   <li>字符都在但顺序不对（{@code 痛胃} vs {@code 胃痛}）—— 候选会被取到，必须被 contains 过滤掉；</li>
 *   <li>同一字符在关键字里重复（{@code 默默}）—— 候选取的是「不同字符」的交集；</li>
 *   <li>关键字含词典里根本不存在的字符 —— 必须直接空集；</li>
 *   <li>随机模糊：小字母表上随机造词典与关键字，逐条比对命中序列与总数。</li>
 * </ul>
 */
@DisplayName("DictionaryServiceImpl：倒排取候选与全表扫描逐条一致（P2-9）")
class DictionarySearchTermsTest {

    private static final String ORG = "org-A";
    private static final String TYPE = "symptom";

    private IDictionaryTermStore termStore;
    private DictionaryServiceImpl svc;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("currentOrgId", ORG);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        termStore = mock(IDictionaryTermStore.class);
        svc = new DictionaryServiceImpl(mock(IEsTermIndexService.class), mock(DistLock.class),
                termStore, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 桩：三档作用域都返回「按该作用域词条建出的索引」（本测试只验证取候选的正确性） */
    private void stubDictionary(List<TermEntry> entries) {
        when(termStore.recallIndex(any(), any(), any()))
                .thenReturn(DictionaryTermStoreImpl.buildRecallIndex(entries));
    }

    /** 改造前的实现：全量扫描 + 包含匹配。作为等价性基准 */
    private static List<String> bruteForce(List<TermEntry> entries, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<String> out = new ArrayList<>();
        for (TermEntry e : entries) {
            boolean matched = kw.isEmpty()
                    || (e.getStandardTerm() != null && e.getStandardTerm().contains(kw))
                    || (e.getAliases() != null
                        && e.getAliases().stream().anyMatch(a -> a != null && a.contains(kw)));
            if (matched) {
                out.add(e.getStandardTerm());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> standardTermsOf(Map<String, Object> result) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) result.get("terms")) {
            out.add((String) m.get("standardTerm"));
        }
        return out;
    }

    private static TermEntry entry(String standardTerm, String... aliases) {
        TermEntry e = new TermEntry();
        e.setStandardTerm(standardTerm);
        e.setAliases(new ArrayList<>(List.of(aliases)));
        return e;
    }

    private void assertSameAsBruteForce(List<TermEntry> entries, String keyword) throws Exception {
        stubDictionary(entries);
        Map<String, Object> actual = svc.searchTerms(TYPE, keyword, 0, 0, "effective");
        List<String> expected = bruteForce(entries, keyword);
        assertEquals(expected, standardTermsOf(actual), "命中序列必须与全表扫描逐条一致：kw=" + keyword);
        assertEquals(expected.size(), actual.get("total"), "total 必须是命中总数：kw=" + keyword);
    }

    @Test
    @DisplayName("关键字命中标准词 / 命中别名 / 字符都在但顺序不对 / 含词典没有的字")
    void basicEquivalence() throws Exception {
        List<TermEntry> dict = List.of(
                entry("胃痛", "胃脘痛", "脘腹痛"),
                entry("痛经", "经行腹痛"),
                entry("不寐", "失眠"),
                entry("默默无语", "沉默"));

        assertSameAsBruteForce(dict, "胃");
        assertSameAsBruteForce(dict, "胃痛");
        assertSameAsBruteForce(dict, "脘腹痛");   // 只在别名里
        assertSameAsBruteForce(dict, "腹痛");     // 命中两个词条的别名
        assertSameAsBruteForce(dict, "痛胃");     // 字符都在，但 contains 为假 → 必须 0 命中
        assertSameAsBruteForce(dict, "龘");       // 词典里没有这个字 → 直接空集
        assertSameAsBruteForce(dict, "默默");     // 同字符重复：候选取「不同字符」的交集
        assertSameAsBruteForce(dict, "默");
        assertSameAsBruteForce(dict, "");
        assertSameAsBruteForce(dict, null);
    }

    @Test
    @DisplayName("命中顺序与词条表顺序一致（不是倒排表的字符顺序）")
    void orderFollowsDictionaryOrder() throws Exception {
        // 「痛」出现在多条词条里；结果必须按词条表顺序，而不是按某字符的倒排表顺序
        List<TermEntry> dict = List.of(
                entry("痛经"),
                entry("头痛"),
                entry("胃痛"));
        stubDictionary(dict);

        List<String> hits = standardTermsOf(svc.searchTerms(TYPE, "痛", 0, 0, "effective"));

        assertEquals(List.of("痛经", "头痛", "胃痛"), hits);
    }

    @Test
    @DisplayName("分页：切片与 total 与改造前一致，越界页码返回空列表而不是抛异常")
    void paginationUnchanged() throws Exception {
        List<TermEntry> dict = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dict.add(entry("痛症" + i));
        }
        stubDictionary(dict);

        Map<String, Object> p1 = svc.searchTerms(TYPE, "痛", 1, 3, "effective");
        assertEquals(List.of("痛症0", "痛症1", "痛症2"), standardTermsOf(p1));
        assertEquals(7, p1.get("total"), "total 是命中总数而不是本页条数");

        Map<String, Object> p3 = svc.searchTerms(TYPE, "痛", 3, 3, "effective");
        assertEquals(List.of("痛症6"), standardTermsOf(p3));

        Map<String, Object> outOfRange = svc.searchTerms(TYPE, "痛", 99, 3, "effective");
        assertTrue(standardTermsOf(outOfRange).isEmpty(), "越界页码必须空列表");
        assertEquals(7, outOfRange.get("total"));
    }

    @Test
    @DisplayName("模糊：小字母表上随机造词典与关键字，命中序列与总数逐条一致")
    void fuzzEquivalence() throws Exception {
        char[] alphabet = "胃痛经不寐失默默腹脘肝脾".toCharArray();
        Random rnd = new Random(20261010L);
        for (int round = 0; round < 200; round++) {
            int size = 1 + rnd.nextInt(40);
            List<TermEntry> dict = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                dict.add(entry(randomWord(rnd, alphabet), randomWord(rnd, alphabet),
                        randomWord(rnd, alphabet)));
            }
            String kw = rnd.nextInt(6) == 0 ? "" : randomWord(rnd, alphabet);
            assertSameAsBruteForce(dict, kw);
        }
    }

    private static String randomWord(Random rnd, char[] alphabet) {
        int len = 1 + rnd.nextInt(4);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet[rnd.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }
}
