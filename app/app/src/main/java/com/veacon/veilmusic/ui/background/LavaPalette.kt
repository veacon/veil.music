package com.veacon.veilmusic.ui.background

/** Keeps artwork hues, including neutral covers, while reserving dark space behind the text. */
internal object LavaPalette {
    fun from(colors: IntArray): FloatArray {
        val source = colors.toList().ifEmpty { listOf(0xFF33333B.toInt()) }
        fun channels(c: Int) = floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)
        fun chroma(c: Int): Float = channels(c).let { it.max() - it.min() }
        // The extractor puts the largest color population first. A tiny saturated detail
        // (for example skin on a blue cover) must not take over the whole background.
        val primary = source.first()
        val secondary = source.maxBy { ArtworkPalette.distance(primary, it) * (chroma(it) + .12f) }
        val darkest = source.minBy { c -> channels(c).let { it[0] * .2126f + it[1] * .7152f + it[2] * .0722f } }
        val ordered = listOf(darkest, primary, secondary, source.getOrElse(2) { primary }, source.getOrElse(3) { secondary })
        return FloatArray(15).also { result ->
            ordered.forEachIndexed { index, color ->
                val rgb = channels(color)
                val high = rgb.max().coerceAtLeast(.001f)
                val target = if (index == 0) high.coerceIn(.12f, .21f) else high.coerceIn(.24f, .49f)
                val scale = target / high
                for (channel in 0..2) result[index * 3 + channel] = (rgb[channel] * scale).coerceIn(0f, 1f)
            }
        }
    }
}

/** Rebased color interpolation; a late artwork decode cannot restart a crossfade from black. */
internal class LavaPaletteTransition(initial: FloatArray) {
    private val current = initial.copyOf()
    private val from = initial.copyOf()
    private val target = initial.copyOf()
    private var startNanos = Long.MIN_VALUE
    private val durationNanos = 1_800_000_000L

    fun retarget(next: FloatArray, nowNanos: Long) {
        if (next.contentEquals(target)) return
        sample(nowNanos).copyInto(from)
        next.copyInto(target)
        startNanos = nowNanos
    }

    fun sample(nowNanos: Long): FloatArray {
        val p = if (startNanos == Long.MIN_VALUE) 1f else
            ((nowNanos - startNanos).coerceAtLeast(0L).toDouble() / durationNanos).toFloat().coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        for (i in current.indices) current[i] = from[i] + (target[i] - from[i]) * eased
        return current
    }

    fun isChanging(nowNanos: Long): Boolean = startNanos != Long.MIN_VALUE && nowNanos - startNanos < durationNanos
}
