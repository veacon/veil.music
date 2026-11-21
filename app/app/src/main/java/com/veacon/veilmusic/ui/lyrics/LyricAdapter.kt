package com.veacon.veilmusic.ui.lyrics

import androidx.compose.runtime.State
import com.veacon.veilmusic.data.network.LyricLine as SourceLine
import com.veacon.veilmusic.data.network.LyricWord as SourceWord
import com.veacon.veilmusic.ui.screens.isMostlyCjk

/**
 * 把本项目（后端代理返回）的歌词模型转成 am_atv 的歌词模型。
 *
 * 关键区别：
 * - 本项目是「行级 LRC + 可选逐词」，am_atv 是「TTML 逐词」。
 * - 没有逐词时间时，**必须标成 [LyricTiming.Line]**：这样渲染走 am_atv 的行同步分支
 *   （整行 alpha 渐变 + 行缩放），而不是逐字弹起。否则把一个字的时长平均分摊，
 *   每个字都会「够长」而被判定为长音，出现"整句每个字都在放大"的怪观感。
 * - 有逐词时间时保持 [LyricTiming.Word]，才会走遮罩扫过 + 字符级上浮。
 */
internal fun SourceLine.toKaraokeLyricLine(): LyricLine {
    val mainEnd = endMs.coerceAtLeast(startMs)
    val timingWord = buildKaraokeWords(words, text, startMs, mainEnd)

    return LyricLine(
        words = timingWord,
        joinWithSpaces = words.isNotEmpty() && !isMostlyCjk(text),
        timing = if (timingWord.size > 1) LyricTiming.Word else LyricTiming.Line,
        backgroundWords = background?.let { bg ->
            val bgEnd = bg.endMs.coerceAtLeast(bg.startMs)
            buildKaraokeWords(bg.words, bg.text, bg.startMs, bgEnd)
        }.orEmpty(),
        lineStartMs = startMs,
        lineEndMs = mainEnd,
    )
}

/**
 * 构造逐词单元。只有当逐词文本拼回来与整行文本一致时才采用逐词时间，
 * 否则退回「整行一个词」，避免翻译/罗马音等字段导致文本错位。
 */
private fun buildKaraokeWords(
    words: List<SourceWord>,
    text: String,
    startMs: Long,
    endMs: Long,
): List<LyricWord> {
    val fallback = listOf(
        LyricWord(
            text = text,
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs),
            hasTrailingWhitespace = true,
        ),
    )
    // LRC 只有行级时间：按时长把整行切成字/词，让「逐字」在普通 LRC 上也能成立。
    if (words.isEmpty()) return synthesizeWords(text, startMs, endMs).ifEmpty { fallback }

    val mapped = words.map { word ->
        LyricWord(
            text = word.text,
            startMs = word.startMs,
            endMs = word.endMs.coerceAtLeast(word.startMs),
            hasTrailingWhitespace = true,
        )
    }
    val spaced = !isMostlyCjk(text)
    val joined = mapped.joinToString(if (spaced) " " else "") { it.text }
    return if (joined.trim() == text.trim()) mapped
    else synthesizeWords(text, startMs, endMs).ifEmpty { fallback }
}


/**
 * 行级时间 → 逐字时间（按时长均分）。
 *
 * 中文/日文按字切，西文按空格分词；拼回去与原文一致，交给逐字渲染器擦除。
 * 精度取决于该行时长，但观感与真逐字一致 —— 也是 Apple 之外歌词源唯一可行的做法。
 */
internal fun synthesizeWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
    val end = endMs.coerceAtLeast(startMs)
    if (text.isBlank() || end <= startMs) return emptyList()
    val cjk = isMostlyCjk(text)
    val units = if (cjk) text.map { it.toString() } else text.split(" ").filter { it.isNotBlank() }
    if (units.size <= 1) return emptyList()
    val total = end - startMs
    val n = units.size
    return units.mapIndexed { i, unit ->
        LyricWord(
            text = unit,
            startMs = startMs + total * i / n,
            endMs = if (i == n - 1) end else startMs + total * (i + 1) / n,
            hasTrailingWhitespace = !cjk,
        )
    }
}

/** 把 `() -> Long` 形态的播放进度包装成 Compose 的 [State]，供移植后的渲染器读取。 */
internal class ProgressState(private val read: () -> Long) : State<Long> {
    override val value: Long get() = read()
}
