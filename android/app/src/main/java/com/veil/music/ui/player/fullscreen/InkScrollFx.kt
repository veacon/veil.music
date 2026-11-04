package com.veil.music.ui.player.fullscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.veil.music.data.model.LyricLine
import com.veil.music.lyrics.LyricWordParser
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 《墨卷 Ink Scroll》—— 竖排手卷 · 逐字落墨（移植自上游项目）。
 *
 * 排版：竖排，统一从左往右读。当前句的最后一列贴住右边距，已唱过的句子全部保留、
 * 依次排在左边，一直到屏幕放不下才出画；一句太长、一列装不下时，剩下的部分作为「续列」
 * 排在它的右边，所以从旧到新、从句首到句尾，方向始终一致。
 *
 * 逐字不是「颜色扫过」，而是落笔：字从淡到浓、微微缩放，字心洇开一圈墨晕。
 * 背景是宣纸纹（不用封面实拍图），封面只以朱砂方章落在左下角。
 *
 * 全部动效都由播放位置推导（落笔进度 = 现在 − 该字起始时间），所以天然与音频同步。
 */
@Composable
fun InkScrollFx(
    lyrics: List<LyricLine>,
    /** 播放器的媒体时钟（毫秒，帧级连续）。逐字进度以它为唯一真值。 */
    positionProvider: () -> Long,
    fontSizeSp: Int,
    fontFamily: FontFamily?,
    /** 行距百分比（100 = 原设计 1.46 字高）。 */
    lineGapPct: Int = 100,
    /** 字间距，单位 em × 100（10 = 0.10em）。 */
    letterSpacingPct: Int = 10,
    trackName: String,
    artist: String,
    /** 浅色纸面（宣纸）/ 深色纸面（夜墨）。 */
    light: Boolean,
    modifier: Modifier = Modifier,
) {
    val paper = if (light) InkPaper else InkNight
    val ink = if (light) InkNightInk else InkNightInkLight
    val faint = ink.copy(alpha = 0.32f)
    val seal = if (light) InkSeal else InkSealLight

    if (lyrics.isEmpty()) {
        Box(modifier.fillMaxSize().background(paper), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = ink.copy(alpha = 0.45f), fontSize = 18.sp)
        }
        return
    }

    // 每帧推进一次「心跳」：Canvas 只在绘制阶段读它，于是只触发重绘、不会引起重组。
    val beat = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { beat.floatValue = it / 1_000_000_000f }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(paper)) {
        val density = LocalDensity.current
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val measurer = rememberTextMeasurer(cacheSize = 512)

        // 字号：以屏高为基准（竖排按高度排），同时受用户的歌词字号设置约束。
        val userPx = with(density) { fontSizeSp.sp.toPx() }
        val minFont = max(18f, userPx * 0.9f)
        val maxFont = max(minFont, userPx * 1.7f)
        val fontPx = (hPx * 0.052f).coerceIn(minFont, maxFont)
        val fontSp = fontPx / density.density
        val histFontPx = fontPx * 0.82f
        val ls = (letterSpacingPct / 100f).coerceIn(0f, 0.4f)
        // 字距（竖排 = 行内字与字的垂直间距）：1.46 字高为基准，再叠加用户的行距与字距。
        val charH = fontPx * (1.46f * (lineGapPct / 100f).coerceIn(0.6f, 2.2f) + ls)
        val padH = wPx * 0.055f
        val padV = hPx * 0.10f
        val contentH = (hPx - padV * 2f).coerceAtLeast(fontPx * 4f)
        val charsPerCol = max(4, floor(contentH / charH).toInt())

        // 列间距：1.85 倍字宽，两组之间再留一道空隙。
        val slotCur = fontPx * (1.85f + ls)
        val slotHist = histFontPx * (1.85f + ls)
        // 句子之间的空隙（比列距更宽），用来区分「同一句的续列」和「下一句」。
        val groupGap = fontPx * 0.85f

        val curStyle = TextStyle(
            fontSize = fontSp.sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Medium,
            letterSpacing = ls.em,
        )
        val histStyle = curStyle.copy(fontSize = (fontSp * 0.82f).sp)

        // 纸纹噪点：固定种子，只算一次（不能在绘制阶段调 remember）。
        val speckles = remember { speckleField() }
        // 每句的逐字时间片缓存：切歌时重算一次，绘制阶段直接取。
        val charCache = remember(lyrics) { HashMap<Int, List<InkChar>>() }
        // 换行时整卷左移的缓动（只在绘制阶段读，不触发重组）。
        val slide = remember { InkSlide() }
        // 绘制异常只记一次日志，避免逐帧刷屏。
        var reportedError = false

        /** 真正的绘制体：抽成局部函数，外层包 runCatching —— 视觉特效再怎么样也不该让播放页闪退。 */
        fun DrawScope.render() {
            // 心跳读一次，保证每帧重绘（下面的进度是纯计算，读不到 state 变化）。
            @Suppress("UNUSED_EXPRESSION") beat.floatValue
            if (size.minDimension < 2f) return

            val now = positionProvider()
            val active = activeIndex(lyrics, now)
            if (active < 0) return

            // ---- 纸纹：细噪点 + 极淡横向纤维 ------------------------------------------------
            for (s in speckles) {
                drawCircle(
                    color = ink.copy(alpha = s[3]),
                    radius = s[2],
                    center = Offset(s[0] * size.width, s[1] * size.height),
                )
            }
            for (i in 1..7) {
                val y = size.height * (i / 8f)
                drawRect(
                    color = ink.copy(alpha = 0.018f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, 1f),
                )
            }
            // 四周压一点点，中心透出来（宣纸的「受光」感）。半径必须 > 0。
            val vignetteR = size.maxDimension * 0.75f
            if (vignetteR > 1f) {
                drawRect(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to ink.copy(alpha = 0.10f),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = vignetteR,
                    ),
                )
            }

            // ---- 列排布：统一「从左往右」读 ------------------------------------------------
            val curCols = splitColumns(charCache, lyrics, active, charsPerCol)
            if (curCols.isEmpty()) return
            // 换行时整卷左移：先把旧内容留在原位，再用 420ms 推到位（新句从右边缘滑进来）。
            val curWidth = curCols.size * slotCur + groupGap
            slide.update(active, beat.floatValue, curWidth)
            var x = size.width - padH + slide.offset(beat.floatValue)

            var li = active
            while (li >= 0) {
                val line = lyrics.getOrNull(li) ?: break
                val cols = if (li == active) curCols
                else splitColumns(charCache, lyrics, li, charsPerCol)
                val isCur = li == active
                val slot = if (isCur) slotCur else slotHist
                val style = if (isCur) curStyle else histStyle
                val step = if (isCur) charH else charH * 0.82f
                val alphaMul = if (isCur) 1f else 0.52f

                for (ci in cols.indices.reversed()) {
                    val cx = x - slot / 2f
                    // 这一列已经整列落在屏幕左侧之外：再往左只会更远，直接收工。
                    if (cx + slot / 2f < 0f) return

                    val chars = cols[ci].chars
                    val colTop = (size.height - chars.size * step) / 2f

                    for (k in chars.indices) {
                        val ch = chars[k]
                        val age = now - ch.startMs
                        val written = age >= 0
                        val p = if (written) (age / 180f).coerceIn(0f, 1f) else 0f
                        val ease = p * p * (3f - 2f * p)
                        // 未写 = 极淡的「预告」；写的过程 = 落笔（淡 → 浓，缩放 0.86 → 1）
                        val baseAlpha = if (!written) 0.16f else 0.16f + 0.84f * ease
                        val scale = if (!written) 1f else 0.86f + 0.14f * ease
                        // 拖腔：单字唱超过 1.2 秒时轻微呼吸
                        val hold = (ch.endMs - ch.startMs) >= 1200L && written &&
                            age <= (ch.endMs - ch.startMs)
                        val breathe = if (hold) {
                            1f + 0.04f * sin(beat.floatValue * 2f * PI.toFloat() * 1.2f)
                        } else {
                            1f
                        }
                        val alpha = (baseAlpha * alphaMul).coerceIn(0f, 1f)
                        if (alpha <= 0.01f) continue

                        val cy = colTop + k * step
                        val layout: TextLayoutResult = measurer.measure(ch.ch, style)
                        val w = layout.size.width
                        val h = layout.size.height
                        val center = Offset(cx, cy + h * 0.5f)

                        // 墨晕：刚落笔的字，字心扩散一圈淡墨（半径必须 > 0）
                        if (written && age < 300L) {
                            val t = age / 300f
                            val r = fontPx * 1.4f * t
                            if (r > 0.8f) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        listOf(ink.copy(alpha = 0.42f * (1f - t)), Color.Transparent),
                                        center = center,
                                        radius = r,
                                    ),
                                    radius = r,
                                    center = center,
                                )
                            }
                        }

                        withTransform({
                            translate(center.x, center.y)
                            scale(scale * breathe, scale * breathe)
                            translate(-center.x, -center.y)
                        }) {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(cx - w / 2f, cy),
                                color = ink,
                                alpha = alpha,
                            )
                        }
                    }

                    x -= slot
                }

                // 句子之间让开一道比列距更宽的空隙，避免「续列」与「下一句」混淆
                x -= groupGap
                li--
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            // 兜底：绘制异常只丢这一帧，不影响播放（异常记一次日志，方便定位）。
            runCatching { render() }.onFailure { err ->
                if (!reportedError) {
                    reportedError = true
                    android.util.Log.e("InkScrollFx", "draw failed", err)
                }
            }
        }

        // ---- 左下角：朱砂方章（落款）+ 歌名 / 歌手 ------------------------------------------
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 40.dp, bottom = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(seal, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = trackName.take(1).ifEmpty { "歌" },
                    color = paper,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(trackName, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Text(artist, color = faint, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// ============== 数据结构与工具 ==============================================================

/** 一个字（含它在句中的位置与音节时间）。 */
private class InkChar(
    val ch: String,
    val startMs: Long,
    val endMs: Long,
)

/** 一列（竖排的一串字）。 */
private class InkColumn(val chars: List<InkChar>)

/**
 * 换行时的「整卷左移」缓动。
 *
 * 每句切换那一刻，新句的列数是已知的，于是可以把旧内容先留在原地、再在 [DURATION] 毫秒内
 * 推到新位置 —— 视觉上就是新的一句从右边缘滑进来，整卷向左走一格。
 * 时间基准用墙钟（[beat]），暂停时也不会卡在半路；跳转（seek / 换歌）时直接定位，不滑。
 */
private class InkSlide {
    private var lastActive = Int.MIN_VALUE
    private var fromSec = 0f
    private var width = 0f

    fun update(active: Int, nowSec: Float, newWidth: Float) {
        if (active != lastActive) {
            // 不是「上一句 → 下一句」的自然推进（首帧 / 跳转 / 换歌）就直接落位。
            val natural = active == lastActive + 1
            fromSec = if (natural) nowSec else nowSec - DURATION
            width = if (natural) newWidth else 0f
            lastActive = active
        }
    }

    /** 还需要往右偏移多少像素：从 [width] 衰减到 0。 */
    fun offset(nowSec: Float): Float {
        if (width <= 0.01f) return 0f
        val p = ((nowSec - fromSec) / DURATION).coerceIn(0f, 1f)
        val ease = 1f - (1f - p) * (1f - p)   // easeOutQuad
        return width * (1f - ease)
    }

    private companion object {
        const val DURATION = 0.42f             // 秒
    }
}

/** 当前行（按 startMs 二分）。 */
internal fun activeIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    var lo = 0
    var hi = lyrics.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (lyrics[mid].startMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** 取某一句的逐字时间片：优先用真音节，没有就按标点权重估算。结果缓存复用。 */
private fun lineChars(
    lyrics: List<LyricLine>,
    index: Int,
    cache: HashMap<Int, List<InkChar>>,
): List<InkChar> {
    cache[index]?.let { return it }
    val line = lyrics.getOrNull(index)
    if (line == null) return emptyList()
    // 优先用真音节；行级 LRC 没有逐字数据时按标点权重估算（返回的是音源层模型，这里统一摊平）。
    val spans: List<Triple<String, Long, Long>> = if (line.words.isNotEmpty()) {
        line.words.map { Triple(it.text, it.startMs, it.endMs) }
    } else {
        val endMs = if (line.endMs > line.startMs) line.endMs else line.startMs + 4000L
        LyricWordParser.distribute(line.text, line.startMs, endMs)
            .map { Triple(it.text, it.startMs, it.endMs) }
    }
    val out = ArrayList<InkChar>(spans.size)
    for ((text, startMs, endMs) in spans) {
        // 一个「词」可能是多个字（英文单词 / 标签词）。按 code point 拆，
        // 避免把 emoji 拆成半个代理对（送到排版层会出问题）。
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out += InkChar(String(Character.toChars(cp)), startMs, endMs)
            i += Character.charCount(cp)
        }
    }
    cache[index] = out
    return out
}

/** 一句按列高断成若干列（优先在标点后断开）。 */
private fun splitColumns(
    cache: HashMap<Int, List<InkChar>>,
    lyrics: List<LyricLine>,
    index: Int,
    charsPerCol: Int,
): List<InkColumn> {
    val chars = lineChars(lyrics, index, cache)
    if (chars.isEmpty()) return emptyList()
    val cols = mutableListOf<InkColumn>()
    var i = 0
    while (i < chars.size) {
        var end = min(i + charsPerCol, chars.size)
        if (end < chars.size) {
            val brk = (end downTo (i + charsPerCol / 2)).firstOrNull {
                chars[it - 1].ch in "，。、；：？！,.;:!?"
            }
            if (brk != null) end = brk
        }
        cols += InkColumn(chars.subList(i, end))
        i = end
    }
    return cols
}

/** 纸面噪点：固定种子，换歌不换纹理（避免每帧重新生成）。 */
private fun speckleField(): Array<FloatArray> {
    val rnd = kotlin.random.Random(20261103)
    return Array(220) {
        floatArrayOf(
            rnd.nextFloat(),
            rnd.nextFloat(),
            0.6f + rnd.nextFloat() * 1.4f,
            0.012f + rnd.nextFloat() * 0.022f,
        )
    }
}

// ============== 配色 =========================================================================

/** 宣纸（浅）。 */
internal val InkPaper = Color(0xFFF3EADA)
/** 夜墨（深）：纸底。 */
internal val InkNight = Color(0xFF121110)
/** 墨色：浅纸用焦墨，深纸用米白。 */
internal val InkNightInk = Color(0xFF1B1A17)
internal val InkNightInkLight = Color(0xFFEDE7DA)
/** 朱砂。 */
internal val InkSeal = Color(0xFFB23A2E)
internal val InkSealLight = Color(0xFFE0564B)
