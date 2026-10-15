package com.tcm.ehr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 标点规整口径（数据清洗导出 + 术语归一共用）。
 *
 * <p>要点：重复标点合并、半角转全角<b>仅在紧邻汉字时</b>（保护 36.5 / 8:30 / 英文标点）、
 * 去标点两侧空白；省略号 / 破折号不折叠。</p>
 */
@DisplayName("TextUtil：标点规整与术语首尾标点")
class TextUtilPunctuationTest {

    // ---------------------------------------------------------------- normalizePunctuation

    @Test
    @DisplayName("重复标点合并：。。→。，、、→、，！！！→！")
    void collapsesRepeatedPunctuation() {
        assertEquals("发热。伴咳嗽", TextUtil.normalizePunctuation("发热。。伴咳嗽"));
        assertEquals("腹痛，拒按", TextUtil.normalizePunctuation("腹痛，，，拒按"));
        assertEquals("危重！", TextUtil.normalizePunctuation("危重！！！"));
    }

    @Test
    @DisplayName("半角转全角：仅当紧邻汉字，数字/英文里的 , . : 不动")
    void convertsHalfWidthOnlyWhenAdjacentToCjk() {
        assertEquals("发热，咳嗽", TextUtil.normalizePunctuation("发热,咳嗽"));
        assertEquals("（发热）", TextUtil.normalizePunctuation("(发热)"));
        // 数值与时间不能被破坏
        assertEquals("体温36.5℃", TextUtil.normalizePunctuation("体温36.5℃"));
        assertEquals("8:30", TextUtil.normalizePunctuation("8:30"));
        assertEquals("1,000", TextUtil.normalizePunctuation("1,000"));
        // 纯英文片段保留半角
        assertEquals("a, b", TextUtil.normalizePunctuation("a, b"));
    }

    @Test
    @DisplayName("连续半角句点：紧邻汉字时一并转全角并折叠")
    void collapsesHalfWidthDotsAdjacentToCjk() {
        assertEquals("咽痛。", TextUtil.normalizePunctuation("咽痛.."));
    }

    @Test
    @DisplayName("去标点两侧空白：好转 ，继续 → 好转，继续；好转。 继续 → 好转。继续")
    void trimsSpacesAroundPunctuation() {
        assertEquals("好转，继续", TextUtil.normalizePunctuation("好转 ，继续"));
        assertEquals("好转。继续", TextUtil.normalizePunctuation("好转。 继续"));
        assertEquals("发热，咳嗽", TextUtil.normalizePunctuation("发热 , 咳嗽"));
    }

    @Test
    @DisplayName("省略号与破折号成对合法，不折叠")
    void keepsEllipsisAndDash() {
        assertEquals("等等……", TextUtil.normalizePunctuation("等等……"));
        assertEquals("突然——加重", TextUtil.normalizePunctuation("突然——加重"));
    }

    @Test
    @DisplayName("null / 空串原样返回")
    void nullAndEmptyPassThrough() {
        assertEquals(null, TextUtil.normalizePunctuation(null));
        assertEquals("", TextUtil.normalizePunctuation(""));
    }

    // ---------------------------------------------------------------- stripPunctuationBoundary

    @Test
    @DisplayName("去术语首尾标点：咽痛。。→咽痛，(发热)→发热")
    void stripsBoundaryPunctuation() {
        assertEquals("咽痛", TextUtil.stripPunctuationBoundary("咽痛。。"));
        assertEquals("发热", TextUtil.stripPunctuationBoundary("(发热)"));
        assertEquals("胃痛", TextUtil.stripPunctuationBoundary("胃痛。"));
    }

    @Test
    @DisplayName("只去首尾，不动词中：天麻10g、中药不误伤")
    void keepsInnerContent() {
        assertEquals("天麻10g", TextUtil.stripPunctuationBoundary("天麻10g"));
        assertEquals("", TextUtil.stripPunctuationBoundary("。。。"));
        assertEquals(null, TextUtil.stripPunctuationBoundary(null));
        assertEquals("", TextUtil.stripPunctuationBoundary(""));
    }
}
