package com.veacon.veilmusic.ui.background

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Population-weighted color groups, separated in perceptual OKLab space after display tuning. */
internal object ArtworkPalette {
    private data class Candidate(val color: Int, var count: Int, val lab: DoubleArray)

    fun extract(pixels: IntArray, count: Int = 5): List<Int> {
        if (count <= 0) return emptyList()
        val bins = HashMap<Int, LongArray>()
        for (color in pixels) {
            if (color ushr 24 < 128) continue
            val r = color shr 16 and 255
            val g = color shr 8 and 255
            val b = color and 255
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val sum = bins.getOrPut(key) { LongArray(4) }
            sum[0]++; sum[1] += r; sum[2] += g; sum[3] += b
        }
        if (bins.isEmpty()) return emptyList()
        val groups = ArrayList<Candidate>()
        for (sum in bins.values.sortedByDescending { it[0] }) {
            val color = displayColor(
                ((sum[1] / sum[0]).toInt() shl 16) or
                    ((sum[2] / sum[0]).toInt() shl 8) or (sum[3] / sum[0]).toInt(),
            )
            val lab = oklab(color)
            val near = groups.firstOrNull { distance(it.lab, lab) < 0.055 }
            if (near != null) near.count += sum[0].toInt()
            else groups += Candidate(color, sum[0].toInt(), lab)
        }
        // Ignore isolated compression/noise pixels when genuine color groups exist.
        val minimumPopulation = max(2, pixels.size / 500)
        val candidates = groups.filter { it.count >= minimumPopulation }.ifEmpty { groups }
            .sortedByDescending { it.count }.toMutableList()
        val selected = ArrayList<Candidate>(count)
        selected += candidates.removeAt(0)
        for (minimumDistance in doubleArrayOf(0.14, 0.11, 0.08, 0.055, 0.0)) {
            while (selected.size < count && candidates.isNotEmpty()) {
                val eligible = candidates.filter { c -> selected.all { distance(c.lab, it.lab) >= minimumDistance } }
                val next = eligible.maxByOrNull { c ->
                    val separation = selected.minOf { distance(c.lab, it.lab) }
                    separation * (0.6 + 0.4 * sqrt(c.count.toDouble() / pixels.size.coerceAtLeast(1)))
                } ?: break
                selected += next
                candidates.remove(next)
            }
            if (selected.size == count) break
        }
        val result = selected.map { it.color }.toMutableList()
        // A monochrome cover should stay monochrome, rather than inventing unrelated hues.
        var index = 0
        while (result.size < count) result += selected[index++ % selected.size].color
        return result
    }

    internal fun distance(first: Int, second: Int): Double = distance(oklab(first), oklab(second))

    private fun distance(a: DoubleArray, b: DoubleArray): Double = sqrt(
        (a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2),
    )

    private fun displayColor(rgb: Int): Int {
        val r = (rgb shr 16 and 255) / 255f
        val g = (rgb shr 8 and 255) / 255f
        val b = (rgb and 255) / 255f
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val chroma = high - low
        val saturation = if (high == 0f) 0f else chroma / high
        val value = (high * 1.08f).coerceIn(0.20f, 0.94f)
        val sat = (saturation * 1.18f).coerceAtMost(1f)
        // Preserve neutral artwork: clamping saturation to 45% turned all grays red.
        if (chroma < 0.0001f) {
            val gray = (value * 255).roundToInt()
            return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        val hue = when (high) {
            r -> ((g - b) / chroma + 6f) % 6f
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        }
        val c = value * sat
        val x = c * (1 - abs(hue % 2 - 1))
        val m = value - c
        val channels = when (hue.toInt()) {
            0 -> floatArrayOf(c, x, 0f)
            1 -> floatArrayOf(x, c, 0f)
            2 -> floatArrayOf(0f, c, x)
            3 -> floatArrayOf(0f, x, c)
            4 -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        return (0xFF shl 24) or (((channels[0] + m) * 255).roundToInt() shl 16) or
            (((channels[1] + m) * 255).roundToInt() shl 8) or ((channels[2] + m) * 255).roundToInt()
    }

    private fun oklab(color: Int): DoubleArray {
        fun linear(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(color shr 16 and 255)
        val g = linear(color shr 8 and 255)
        val b = linear(color and 255)
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }
}
