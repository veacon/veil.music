package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.data.network.LyricWord

/** 逐字高亮的最小单元：一段文本 + 它自己的起止时间。 */
internal data class LyricCharSpan(val text: String, val startMs: Long, val endMs: Long)

/**
 * 把歌词行的时间片拆到「字」粒度。
 *
 * Apple 返回的 words 多是词或音节，一整句中文常常就是一个 word —— 直接按 word 点亮，
 * 看起来只是「整句一起变白」，没有逐字的感觉。这里把中日韩文本按字符均分该词时长，
 * 让中文能逐字点亮；西文单词保持整词，避免把 "mature" 拆成两半后读起来断裂。
 *
 * 纯函数：不依赖 Compose / 播放器，便于单测。
 */
internal fun lyricCharSpans(words: List<LyricWord>): List<LyricCharSpan> {
    if (words.isEmpty()) return emptyList()
    val out = ArrayList<LyricCharSpan>(words.size * 2)
    for (word in words) {
        val text = word.text
        if (text.isEmpty()) continue
        val start = word.startMs
        val end = word.endMs.coerceAtLeast(start)
        val span = end - start
        if (text.length <= 1 || !isMostlyCjk(text) || span <= 0L) {
            out.add(LyricCharSpan(text, start, end))
            continue
        }
        val n = text.length
        for (i in 0 until n) {
            val charStart = start + span * i / n
            val charEnd = if (i == n - 1) end else start + span * (i + 1) / n
            out.add(LyricCharSpan(text[i].toString(), charStart, charEnd))
        }
    }
    return out
}

/** 一半以上是中日韩表意字符就算「按字切」的文本。 */
internal fun isMostlyCjk(text: String): Boolean {
    if (text.isEmpty()) return false
    val cjk = text.count { ch ->
        val c = ch.code
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFAFF ||
            c in 0x3040..0x30FF || c in 0xAC00..0xD7AF
    }
    return cjk * 2 >= text.length
}

/**
 * 行级歌词（普通 LRC，没有 words）的逐字时间合成。
 *
 * 五大平台的歌词绝大多数只有「一行什么时候开始、什么时候结束」，没有逐字时间。
 * 想在这种歌词上做逐字点亮，只能把整行时长按字/词均分 —— 观感上与逐字歌词一致，
 * 只是精度取决于该行时长。中文按字切，西文按空格分词（避免把单词劈开）。
 */
internal fun estimateCharSpans(text: String, startMs: Long, endMs: Long): List<LyricCharSpan> {
    if (text.isEmpty() || endMs <= startMs) return emptyList()
    val units: List<String> =
        if (isMostlyCjk(text)) text.map { it.toString() }
        else text.split(" ").filter { it.isNotEmpty() }
    if (units.isEmpty()) return emptyList()
    val total = endMs - startMs
    val n = units.size
    return units.mapIndexed { i, unit ->
        val s = startMs + total * i / n
        val e = if (i == n - 1) endMs else startMs + total * (i + 1) / n
        LyricCharSpan(unit, s, e)
    }
}
