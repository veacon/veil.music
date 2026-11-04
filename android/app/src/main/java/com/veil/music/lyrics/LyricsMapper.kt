package com.veil.music.lyrics

import com.veil.music.data.model.LyricLine as UiLyricLine
import com.veil.music.data.model.LyricWord as UiLyricWord

/**
 * 把音源解析出的歌词（[LyricLine]，时间单位秒）转换成播放页使用的毫秒结构。
 *
 * 行级 LRC 没有 end 时间，这里按「下一行的开始时间」补齐；最后一行用逐字切片的结束时间，
 * 没有逐字则给 6 秒的兜底，避免最后一句永远不结束。
 */
object LyricsMapper {

    private const val LAST_LINE_FALLBACK_MS = 6_000L

    fun toUi(parsed: List<LyricLine>): List<UiLyricLine> {
        val timed = parsed.filter { it.time >= 0 && it.text.isNotBlank() }
        if (timed.isEmpty()) return emptyList()

        return timed.mapIndexed { index, line ->
            val startMs = (line.time * 1000.0).toLong()
            val nextStart = timed.getOrNull(index + 1)?.let { (it.time * 1000.0).toLong() }
            val words = line.words.map { UiLyricWord(it.startMs, it.endMs, it.text) }
            val endMs = when {
                nextStart != null && nextStart > startMs -> nextStart
                words.isNotEmpty() -> words.last().endMs
                else -> startMs + LAST_LINE_FALLBACK_MS
            }
            UiLyricLine(
                startMs = startMs,
                endMs = endMs,
                text = line.text,
                words = words,
            )
        }
    }

    /** 行级 LRC 也要有逐字效果：按标点权重把这句摊开。 */
    fun ensureWords(line: UiLyricLine): UiLyricLine {
        if (line.words.isNotEmpty()) return line
        val words = LyricWordParser.distribute(line.text, line.startMs, line.endMs)
            .map { UiLyricWord(it.startMs, it.endMs, it.text) }
        return line.copy(words = words)
    }
}
