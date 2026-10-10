package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1-16 / S3：模糊级 Dice 的<b>口径等价性</b>。
 *
 * <p>这次只动「怎么算」，不动「算出来是多少」—— 而模糊判定直接决定实体归到哪个标准词，
 * 差一位就可能把「胃脘痛」归成别的证候。所以把<b>改造前的 HashSet 实现</b>原样留作参照，
 * 逐对逐位比对。</p>
 *
 * <p>位图方案在本项目被否掉的原因见 {@code EsTermNormalizer.dice} 的注释：
 * BMP 位图 8KB/串、紧凑字母表也要 94 次 popcount，而中文术语只有 3~6 个字，
 * 零分配的原地比较更省也更快。等价性测试保证这个替换没有改变任何一位结果。</p>
 */
class EsTermNormalizerDiceTest {

    /** 参照实现：改造前的「两个 HashSet + 装箱」口径 */
    private static double byHashSet(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<Character> sa = new HashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        Set<Character> sb = new HashSet<>();
        for (char c : b.toCharArray()) sb.add(c);
        int inter = 0;
        for (Character c : sa) {
            if (sb.contains(c)) inter++;
        }
        return 2.0 * inter / (sa.size() + sb.size());
    }

    @Test
    void handPickedCasesMatchOldImplementation() {
        String[] samples = {
                "", "咽痛", "咽喉痛", "嗓子疼", "头晕", "头晕头重", "多饮多食多尿",
                "胃痛", "胃脘痛", "默默不欲饮食", "默默", "郁郁", "咳", "喘",
                "A", "AB", "AAB", "abc", "ABC", "aabb", "口", "口口", "口口口",
                null,
        };
        for (String a : samples) {
            for (String b : samples) {
                assertEquals(byHashSet(a, b), EsTermNormalizer.dice(a, b),
                        "dice(" + a + ", " + b + ") 与改造前不一致");
            }
        }
    }

    /** 随机长串 + 重复字符：多重集与去重语义最容易在这里被写错 */
    @Test
    void randomCasesMatchOldImplementation() {
        Random rnd = new Random(20261010L);
        String[] alphabet = {"头", "晕", "痛", "胃", "脘", "咽", "喉", "a", "B", "1", "口"};
        for (int i = 0; i < 20000; i++) {
            String a = randomString(rnd, alphabet);
            String b = randomString(rnd, alphabet);
            assertEquals(byHashSet(a, b), EsTermNormalizer.dice(a, b),
                    "dice(" + a + ", " + b + ") 与改造前不一致");
        }
    }

    /** 完全相同的两串必然得 1；零交集必然得 0（守住两个端点） */
    @Test
    void endpointsHold() {
        assertEquals(1.0, EsTermNormalizer.dice("咽喉痛", "咽喉痛"));
        assertEquals(1.0, EsTermNormalizer.dice("默默", "默默"));
        assertEquals(0.0, EsTermNormalizer.dice("头晕", "胃痛"));
        assertEquals(0.0, EsTermNormalizer.dice("", "头晕"));
        assertEquals(0.0, EsTermNormalizer.dice("头晕", null));
    }

    private static String randomString(Random rnd, String[] alphabet) {
        int len = rnd.nextInt(9);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet[rnd.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }
}
