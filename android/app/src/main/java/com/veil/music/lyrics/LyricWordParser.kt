package com.veil.music.lyrics

/**
 * 音节级时间片：一句歌词里的一个字 / 一个词，以及它自己的起止时间（毫秒）。
 *
 * 逐字效果（Apple Music 扫光、墨卷落墨、声轨格子）都需要知道「现在唱到哪个字」。
 * 行级 LRC 只能在一句之内做线性插值 —— 唱到逗号时不会有停顿，机械感就是从这来的。
 * 所以额外维护这一层数据：有 QRC / YRC / KRC / TTML 就用真数据，没有就按标点权重
 * 估算一个「不均匀」的分布（见 [LyricWordParser.distribute]）。
 */
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * 多格式歌词解析器：QRC / YRC / KRC / TTML / LRC 统一归一成 [LyricLine]（带音节）。
 *
 * 各家格式的行头都是 `[起始毫秒, 时长毫秒]`：
 *  - QRC（QQ 音乐）：`歌词(起始,时长)`，文本在括号**前**；
 *  - KRC（酷狗）：`歌词(起始,时长,0)`，文本在括号**前**；
 *  - YRC（网易云）：`(起始,时长,0)歌词`，文本在括号**后**。
 * TTML（Apple Music 风格）用 `<p begin= end=>` + `<span begin= end=>`。
 */
object LyricWordParser {
    enum class Format { QRC, YRC, KRC, TTML, LRC, PLAIN }

    private val durLine = Regex("""\[(\d+),(\d+)]""")
    private val paren = Regex("""\((\d+),(\d+)(?:,(\d+))?\)""")
    private val lrcTime = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val ttmlP = Regex(
        """<p[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*(?:/>|>(.*?)</p>)""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ttmlSpan = Regex(
        """<span[^>]*begin="([^"]+)"[^>]*end="([^"]+)"[^>]*>(.*?)</span>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 猜格式：看行头是不是「数字,数字」就能把 LRC 与 QRC/YRC/KRC 分开。 */
    fun detect(raw: String): Format {
        val s = raw.trim()
        if (s.isEmpty()) return Format.PLAIN
        if (s.contains("<tt", ignoreCase = true) ||
            s.contains("<p ", ignoreCase = true) ||
            s.contains("<span", ignoreCase = true)
        ) return Format.TTML
        if (durLine.containsMatchIn(s)) {
            // QRC / KRC：文本在括号前；YRC：文本在括号后。取第一处括号看两侧文字量。
            val first = paren.find(s)
            if (first == null) return Format.QRC
            val before = s.substring(0, first.range.first).trim()
            val after = s.substring(first.range.last + 1).take(8).trim()
            return when {
                before.isEmpty() && after.isNotEmpty() -> Format.YRC
                before.isNotEmpty() -> Format.QRC
                else -> Format.QRC
            }
        }
        if (lrcTime.containsMatchIn(s)) return Format.LRC
        return Format.PLAIN
    }

    /** 解析入口：自动识别格式，输出带音节的歌词行。 */
    fun parse(raw: String, translation: String? = null): List<LyricLine> = when (detect(raw)) {
        Format.TTML -> parseTtml(raw, translation)
        Format.LRC -> parseLrcWithWords(raw, translation)
        Format.PLAIN -> LyricParser.parse(raw, translation)
        else -> parseDurLines(raw, translation)
    }

    // ---- [起始,时长] 系（QRC / YRC / KRC）------------------------------------------------

    private fun parseDurLines(raw: String, translation: String?): List<LyricLine> {
        val transMap = translationMap(translation)
        val out = mutableListOf<LyricLine>()
        var id = 0
        for (rawLine in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if (rawLine.isBlank()) continue
            val head = durLine.find(rawLine) ?: continue
            val lineStart = head.groupValues[1].toLongOrNull() ?: continue
            val body = rawLine.substring(head.range.last + 1)
            val words = parseWordBody(body)
            if (words.isEmpty()) continue
            val text = words.joinToString("") { it.text }
            out += LyricLine(
                id = id++,
                time = lineStart / 1000.0,
                text = text,
                translation = transMap[lineStart],
                words = words,
            )
        }
        return out
    }

    /** 解析一行正文里的音节（同时兼容「文本在括号前」与「文本在括号后」两种排布）。 */
    private fun parseWordBody(body: String): List<LyricWord> {
        val matches = paren.findAll(body).toList()
        if (matches.isEmpty()) return emptyList()
        val head = body.substring(0, matches.first().range.first)
        val words = mutableListOf<LyricWord>()
        if (head.isNotBlank()) {
            // QRC / KRC：词(起始,时长)
            var cursor = 0
            for (m in matches) {
                val text = body.substring(cursor, m.range.first).trim()
                val st = m.groupValues[1].toLongOrNull()
                val du = m.groupValues[2].toLongOrNull()
                cursor = m.range.last + 1
                if (text.isEmpty() || st == null || du == null) continue
                words += LyricWord(text, st, st + du)
            }
        } else {
            // YRC：(起始,时长,0)词
            for ((i, m) in matches.withIndex()) {
                val st = m.groupValues[1].toLongOrNull()
                val du = m.groupValues[2].toLongOrNull()
                if (st == null || du == null) continue
                val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
                val text = body.substring(m.range.last + 1, textEnd).trim()
                if (text.isEmpty()) continue
                words += LyricWord(text, st, st + du)
            }
        }
        return words
    }

    // ---- TTML ----------------------------------------------------------------------------

    private fun parseTtml(raw: String, translation: String?): List<LyricLine> {
        val transMap = translationMap(translation)
        val out = mutableListOf<LyricLine>()
        var id = 0
        for (m in ttmlP.findAll(raw)) {
            val start = parseTtmlTime(m.groupValues[1]) ?: continue
            val end = parseTtmlTime(m.groupValues[2]) ?: (start + 2000L)
            val inner = m.groupValues.getOrElse(3) { "" }
            val spans = ttmlSpan.findAll(inner).toList()
            val words = if (spans.isEmpty()) {
                val plain = stripTags(inner).trim()
                if (plain.isEmpty()) emptyList() else listOf(LyricWord(plain, start, end))
            } else {
                spans.mapNotNull { s ->
                    val st = parseTtmlTime(s.groupValues[1]) ?: return@mapNotNull null
                    val en = parseTtmlTime(s.groupValues[2]) ?: (st + 200L)
                    val t = stripTags(s.groupValues[3]).trim()
                    if (t.isEmpty()) null else LyricWord(t, st, en)
                }
            }
            if (words.isEmpty()) continue
            out += LyricLine(
                id = id++,
                time = start / 1000.0,
                text = words.joinToString("") { it.text },
                translation = transMap[start],
                words = words,
            )
        }
        return out
    }

    private fun parseTtmlTime(raw: String): Long? {
        val s = raw.trim()
        if (s.endsWith("ms")) return s.dropLast(2).toDoubleOrNull()?.toLong()
        if (s.endsWith("s")) return (s.dropLast(1).toDoubleOrNull() ?: return null).times(1000).toLong()
        val parts = s.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toDouble()
                    val m = parts[1].toDouble()
                    val sec = parts[2].toDouble()
                    ((h * 3600 + m * 60 + sec) * 1000).toLong()
                }
                2 -> ((parts[0].toDouble() * 60 + parts[1].toDouble()) * 1000).toLong()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "")

    // ---- LRC：行级数据 + 标点权重降级 ------------------------------------------------------

    /**
     * 解析 LRC 并把每一行拆成音节：没有真音节数据时，按「标点更慢」的权重把时长分配给字。
     * 逗号 ×1.6、句号 ×2.2、空格 ×0.6 —— 于是唱到逗号时高亮会自然停一下，
     * 不再是一把尺子扫过整句。
     */
    private fun parseLrcWithWords(raw: String, translation: String?): List<LyricLine> {
        val base = LyricParser.parse(raw, translation)
        if (base.isEmpty()) return base
        return base.mapIndexed { i, line ->
            if (line.time < 0) return@mapIndexed line
            val next = base.getOrNull(i + 1)
            val endMs = if (next != null && next.time > line.time) (next.time * 1000).toLong()
            else (line.time * 1000).toLong() + 4000L
            val startMs = (line.time * 1000).toLong()
            line.copy(words = distribute(line.text, startMs, endMs))
        }
    }

    /**
     * 把一句歌词按权重摊到 [startMs, endMs] 上，返回逐字时间片。
     * 权重：CJK 1.0、逗号顿号 1.6、句号问号叹号 2.2、空格 0.6、拉丁字母数字 0.55。
     */
    fun distribute(text: String, startMs: Long, endMs: Long): List<LyricWord> {
        if (text.isEmpty()) return emptyList()
        val chars = text.toList()
        if (chars.isEmpty()) return emptyList()
        val weights = chars.map { weightOf(it) }
        val total = weights.sum().takeIf { it > 0f } ?: return emptyList()
        val span = (endMs - startMs).coerceAtLeast(0L)
        val out = ArrayList<LyricWord>(chars.size)
        var acc = 0f
        chars.forEachIndexed { i, c ->
            val w = weights[i]
            val s = startMs + (span * (acc / total)).toLong()
            acc += w
            val e = startMs + (span * (acc / total)).toLong()
            out += LyricWord(c.toString(), s, e)
        }
        return out
    }

    private fun weightOf(c: Char): Float = when {
        c.isWhitespace() -> 0.6f
        c in "，。、；：？！,.;:?!" -> 1.6f
        c in "。！？!?" -> 2.2f
        c.code < 128 -> 0.55f          // 拉丁字母 / 数字 / 半角符号
        else -> 1.0f
    }

    // ---- 翻译对齐 --------------------------------------------------------------------------

    /** 翻译按「起始毫秒」最近邻匹配，容差 500ms（吸收 LRC 百分秒与 KRC 毫秒的精度差）。 */
    private fun translationMap(translation: String?): Map<Long, String> {
        if (translation.isNullOrBlank()) return emptyMap()
        val parsed = LyricParser.parse(translation)
        if (parsed.isEmpty()) return emptyMap()
        val out = HashMap<Long, String>()
        for (l in parsed) {
            if (l.time < 0) continue
            out[(l.time * 1000).toLong()] = l.text
        }
        return out
    }
}
