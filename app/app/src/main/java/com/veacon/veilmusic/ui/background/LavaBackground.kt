package com.veacon.veilmusic.ui.background

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import com.veacon.veilmusic.data.NetworkLog
import com.veacon.veilmusic.media.AudioSpectrum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 真正的熔岩背景：移植自 am_atv 的 OpenGL / CPU 双实现，由真实频谱驱动。
 * 低配或不支持硬件加速时用 CPU 版，其余用 GPU 版；静态/降动效时只显示取色渐变。
 */
@Composable
fun LavaBackground(
    artworkUrl: String?,
    quality: FluidQuality,
    playing: Boolean,
    reduceMotion: Boolean,
    lowPower: Boolean,
    modifier: Modifier = Modifier,
    spectrum: (() -> AudioSpectrum)? = null,
    pulseSpeedMultiplier: Float = 1f,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 取封面主色；无封面或解码失败时使用稳定的深色回退，保证熔岩仍有颜色。
    val colors by produceState<List<Int>>(initialValue = listOf(0xFF33333B.toInt()), artworkUrl) {
        if (artworkUrl.isNullOrBlank()) return@produceState
        try {
            val result = Coil.imageLoader(context).execute(ImageRequest.Builder(context)
                .data(artworkUrl).size(256).allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888).build())
            currentCoroutineContext().ensureActive()
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
            if (bmp != null) {
                value = withContext(Dispatchers.Default) { paletteFrom(bmp) }
            } else NetworkLog.line("Lava", "artwork unavailable; fallback palette")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkLog.line("Lava", "artwork palette failed (${e.javaClass.simpleName}); fallback")
        }
    }
    val palette = remember(colors) { colors.toIntArray() }
    val staticGradient = remember(colors) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            if (colors.size >= 2) colors.map { Color(it) }
            else listOf(Color(0xFF171722), Color(0xFF171722)))
    }

    // 静态/降动效：只画取色渐变，不开任何渲染线程。
    if (quality == FluidQuality.OFF || reduceMotion || artworkUrl == null) {
        Box(modifier.fillMaxSize().background(staticGradient))
        return
    }

    val spectrumSource: () -> AudioSpectrum = spectrum ?: { AudioSpectrum() }
    val running = playing && foreground
    val useCpu = lowPower || Build.VERSION.SDK_INT < 26
    Box(modifier.fillMaxSize().background(Color(0xFF171722))) {
        if (useCpu) {
            val renderer = remember { LavaCpuBackdropController() }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            SideEffect { renderer.update(palette, running, spectrumSource, pulseSpeedMultiplier) }
            AndroidView(
                factory = { LavaCpuBackdropView(it, renderer) },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val renderer = remember { LavaBackdropController() }
            DisposableEffect(renderer) { onDispose { renderer.close() } }
            SideEffect { renderer.update(palette, running, spectrumSource, pulseSpeedMultiplier) }
            AndroidView(
                factory = { LavaBackdropView(it, renderer) },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 从封面位图提取 6 个主色（深色/强调色交替），与播放页 AlbumWall 取色口径一致。 */
private fun paletteFrom(bitmap: Bitmap): List<Int> {
    val p = Palette.from(bitmap).maximumColorCount(24).generate()
    val swatches = listOfNotNull(
        p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
        p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch,
    ) + p.swatches
    if (swatches.isEmpty()) return listOf(0xFF33333B.toInt())
    val maxPop = swatches.maxOf { it.population }.coerceAtLeast(1)
    val sorted = swatches.distinctBy { it.rgb }.sortedByDescending {
        hsvSat(it.rgb) * 0.62f + it.population.toFloat() / maxPop * 0.38f
    }
    val out = mutableListOf<Int>()
    for (s in sorted) {
        out.add(s.rgb)
        if (out.size >= 6) break
    }
    // 保证首色是深色，给文字留出暗背景。
    val darkest = sorted.minByOrNull { luminance(it.rgb) }?.rgb ?: sorted.first().rgb
    out[0] = darkest
    return out
}

private fun hsvSat(rgb: Int): Float {
    val r = (rgb shr 16 and 255) / 255f
    val g = (rgb shr 8 and 255) / 255f
    val b = (rgb and 255) / 255f
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    return if (max <= 0f) 0f else (max - min) / max
}

private fun luminance(rgb: Int): Float {
    val r = (rgb shr 16 and 255) / 255f
    val g = (rgb shr 8 and 255) / 255f
    val b = (rgb and 255) / 255f
    return r * 0.2126f + g * 0.7152f + b * 0.0722f
}
