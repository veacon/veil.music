package com.veacon.veilmusic.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * 封面高斯模糊（近似）：先把封面缩到 [sampleSize] 见方的小图，再跑 3 次盒式模糊
 * （一次水平 + 一次垂直算一轮，3 轮≈高斯）。用于迷你胶囊的「毛玻璃」底。
 *
 * 为什么不用 RenderScript / `Modifier.blur`：
 *  - `Modifier.blur` 依赖 RenderEffect，只有 Android 12（API 31）才有，Android 8 上直接空操作；
 *  - RenderScript 虽然在 Android 8 可用，但在 API 31 起弃用，且个别廉价电视盒子的驱动不稳。
 *  - 56×56 的小图跑 3 轮盒式模糊开销极低，放在 Coil 的后台线程里，全版本行为一致。
 *
 * 产物只当成「背景色块」放大铺底，不做像素级精准，所以小图放大后的柔化正好是想要的玻璃质感。
 */
class ArtworkBlurTransformation(
    private val sampleSize: Int = 56,
    private val radius: Int = 5,
) : Transformation {

    // 相同参数复用同一张缓存，避免每次重组都重新模糊。
    override val cacheKey: String = "artworkBlur-$sampleSize-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = sampleSize.coerceIn(8, 128)
        val ratio = input.height.toFloat() / input.width.coerceAtLeast(1)
        val h = (w * ratio).toInt().coerceIn(8, 128)

        val small = Bitmap.createScaledBitmap(input, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        var buf = pixels
        repeat(3) {
            buf = boxPass(buf, w, h, radius, horizontal = true)
            buf = boxPass(buf, w, h, radius, horizontal = false)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(buf, 0, w, 0, 0, w, h)
        if (small !== out) small.recycle()
        return out
    }
}

/** 滑动窗口盒式模糊，O(w*h)，单方向一遍。 */
private fun boxPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
    val dst = IntArray(src.size)
    val div = 2 * r + 1
    if (horizontal) {
        for (y in 0 until h) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[y * w + i.coerceIn(0, w - 1)]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val po = src[y * w + (x - r).coerceIn(0, w - 1)]
                val pi = src[y * w + (x + r + 1).coerceIn(0, w - 1)]
                a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                g += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                b += (pi and 0xFF) - (po and 0xFF)
            }
        }
    } else {
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += (p ushr 24) and 0xFF
                rr += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                val po = src[(y - r).coerceIn(0, h - 1) * w + x]
                val pi = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += ((pi ushr 24) and 0xFF) - ((po ushr 24) and 0xFF)
                rr += ((pi ushr 16) and 0xFF) - ((po ushr 16) and 0xFF)
                g += ((pi ushr 8) and 0xFF) - ((po ushr 8) and 0xFF)
                b += (pi and 0xFF) - (po and 0xFF)
            }
        }
    }
    return dst
}
