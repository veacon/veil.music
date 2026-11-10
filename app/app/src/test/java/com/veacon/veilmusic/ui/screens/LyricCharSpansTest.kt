package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.data.network.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐字拆分的纯逻辑：中文按字、西文整词，时间片必须铺满且不重叠。 */
class LyricCharSpansTest {

    @Test fun chineseWordIsSplitIntoCharsWithEvenTiming() {
        val spans = lyricCharSpans(listOf(LyricWord(1_000, 1_400, "唱歌")))
        assertEquals(2, spans.size)
        assertEquals("唱", spans[0].text)
        assertEquals("歌", spans[1].text)
        assertEquals(1_000, spans[0].startMs)
        assertEquals(1_200, spans[0].endMs)
        assertEquals(1_200, spans[1].startMs)
        assertEquals(1_400, spans[1].endMs)
    }

    @Test fun latinWordStaysWhole() {
        val spans = lyricCharSpans(listOf(LyricWord(0, 500, "mature")))
        assertEquals(1, spans.size)
        assertEquals("mature", spans[0].text)
        assertEquals(0, spans[0].startMs)
        assertEquals(500, spans[0].endMs)
    }

    @Test fun mixedLineKeepsWordOrderAndFullCoverage() {
        val spans = lyricCharSpans(
            listOf(
                LyricWord(0, 400, "爱你"),
                LyricWord(400, 900, "baby"),
            )
        )
        assertEquals(listOf("爱", "你", "baby"), spans.map { it.text })
        // 时间片连续、首尾对齐整行
        assertEquals(0, spans.first().startMs)
        assertEquals(900, spans.last().endMs)
        for (i in 1 until spans.size) {
            assertEquals(spans[i - 1].endMs, spans[i].startMs)
        }
    }

    @Test fun emptyAndDegenerateInputAreSafe() {
        assertTrue(lyricCharSpans(emptyList()).isEmpty())
        // 零时长（词表异常）不拆字，也不产生负数区间
        val spans = lyricCharSpans(listOf(LyricWord(500, 500, "静")))
        assertEquals(1, spans.size)
        assertEquals(500, spans[0].startMs)
        assertEquals(500, spans[0].endMs)
    }

    @Test fun cjkDetectionMatchesTextKind() {
        assertTrue(isMostlyCjk("晴天"))
        assertTrue(isMostlyCjk("さくら"))
        assertTrue(isMostlyCjk("사랑"))
        assertFalse(isMostlyCjk("mature"))
        assertFalse(isMostlyCjk("Love 你"))
    }

    @Test fun lineLevelLyricsGetSynthesisedCharTiming() {
        // 普通 LRC：只有行起止时间，逐字时间按字均分
        val spans = estimateCharSpans("我的亲爱", 0, 4000)
        assertEquals(listOf("我", "的", "亲", "爱"), spans.map { it.text })
        assertEquals(0, spans.first().startMs)
        assertEquals(4000, spans.last().endMs)
        for (i in 1 until spans.size) assertEquals(spans[i - 1].endMs, spans[i].startMs)
    }

    @Test fun latinLineSplitsByWordNotByLetter() {
        val spans = estimateCharSpans("sayonara my love", 0, 3000)
        assertEquals(listOf("sayonara", "my", "love"), spans.map { it.text })
        assertEquals(3000, spans.last().endMs)
    }

    @Test fun synthesisedTimingIsSkippedForUnsyncedLines() {
        assertTrue(estimateCharSpans("纯文本歌词", -1, -1).isEmpty())
        assertTrue(estimateCharSpans("", 0, 1000).isEmpty())
        assertTrue(estimateCharSpans("abc", 500, 500).isEmpty())
    }
}
