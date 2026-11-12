package com.veacon.veilmusic.util

import com.veacon.veilmusic.data.LyricsScript
import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.data.network.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词简繁转换。
 *
 * 纯 JVM 单测：`Build.VERSION.SDK_INT` 在本地单测里恒为 0 → 走内置兜底字表，
 * 正好覆盖 API 23 那条路径（ICU 路径由真机/联网回归验证）。
 */
class LyricsScriptConverterTest {

    @Test
    fun `繁体歌词转简体`() {
        // 小星星（张震岳）实测就是繁体，用户报的就是这一条。
        val zh = "我們圍在一起，營火已經升起"
        val out = LyricsScriptConverter.convert(zh, LyricsScript.SIMPLIFIED)
        assertTrue("未转成简体: $out", out.contains("在一起"))
        assertTrue("繁体字残留: $out", !out.contains("們"))
        assertTrue(!out.contains("營火"))
    }

    @Test
    fun `简体歌词保持原样`() {
        val s = "我们围在一起，营火已经升起"
        assertEquals(s, LyricsScriptConverter.convert(s, LyricsScript.SIMPLIFIED))
    }

    @Test
    fun `简繁可逆_繁体模式把简体转回去`() {
        val out = LyricsScriptConverter.convert("后来的我们", LyricsScript.TRADITIONAL)
        assertTrue("应为繁体: $out", out.contains("後") || out.contains("來"))
    }

    @Test
    fun `日文与韩文歌词不做转换`() {
        val jp = "君の名前を呼んでいる"
        assertEquals(jp, LyricsScriptConverter.convert(jp, LyricsScript.SIMPLIFIED))
        val kr = "그대 이름을 불러요"
        assertEquals(kr, LyricsScriptConverter.convert(kr, LyricsScript.SIMPLIFIED))
    }

    @Test
    fun `英文与空串不处理`() {
        assertEquals("Hello, World", LyricsScriptConverter.convert("Hello, World", LyricsScript.SIMPLIFIED))
        assertEquals("", LyricsScriptConverter.convert("", LyricsScript.TRADITIONAL))
    }

    @Test
    fun `逐字与背景人声一起转换且时间轴不变`() {
        val lines = listOf(
            LyricLine(
                startMs = 1000, endMs = 2000, text = "後來",
                words = listOf(LyricWord(1000, 1500, "後"), LyricWord(1500, 2000, "來")),
                background = com.veacon.veilmusic.data.network.LyricBackground(
                    startMs = 1000, endMs = 2000, text = "後來", words = listOf(LyricWord(1000, 2000, "來")),
                ),
            ),
        )
        val out = LyricsScriptConverter.convert(lines, LyricsScript.SIMPLIFIED)
        assertEquals(1, out.size)
        assertEquals(1000L, out[0].startMs)
        assertEquals(2000L, out[0].endMs)
        assertEquals("后来", out[0].text)
        assertEquals("后", out[0].words[0].text)
        assertEquals("来", out[0].words[1].text)
        assertEquals("后来", out[0].background?.text)
        assertEquals("来", out[0].background?.words?.first()?.text)
    }

    @Test
    fun `兜底字表繁简一致_不会把简体改错`() {
        // 兜底表只做单向映射，简体输入必须原样返回（否则界面会出现反向错字）。
        for ((t, s) in LyricsScriptConverter.T2S_FALLBACK) {
            assertEquals(s.toString(), LyricsScriptConverter.convert(s.toString(), LyricsScript.SIMPLIFIED))
            // 繁体字经兜底表转换后应得到对应简体字（API 23 路径）。
            assertEquals(s.toString(), LyricsScriptConverter.convert(t.toString(), LyricsScript.SIMPLIFIED))
        }
    }

    @Test
    fun `S2T 反查表由 T2S 生成`() {
        assertTrue(LyricsScriptConverter.S2T_FALLBACK.isNotEmpty())
        assertEquals('後', LyricsScriptConverter.S2T_FALLBACK['后'])
    }
}
