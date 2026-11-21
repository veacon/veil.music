package com.veacon.veilmusic.ui.background

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.R

/**
 * 背景档只决定分辨率与是否动画，帧率交给 [BackgroundFrameRate] 的 30/60 双档
 * （波次2 P0-7：档位语义 = 帧预算，不再有省电/低电量语义）。
 */
enum class FluidQuality(private val labelId: Int, val maxEdge: Int, private val qualityFps: Int) {
    OFF(R.string.msg_004, 0, 0),
    STATIC(R.string.msg_005, 0, 0),
    /** 640p 低分辨率档（原「省电」档，语义已改为纯分辨率档）。 */
    POWER_SAVE(R.string.msg_006, 640, 60),
    STANDARD(R.string.msg_007, 960, 60),
    HIGH_QUALITY(R.string.fluid_quality_high, 1280, 60);
    val label: String get() = tr(labelId)

    /** 每帧预算：0 = 不渲染；否则跟随用户选的 30/60 档。 */
    val fps: Int get() = if (qualityFps == 0) 0 else BackgroundFrameRate.fps

    val animated: Boolean get() = qualityFps > 0

    companion object {
        fun fromName(name: String?): FluidQuality = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

internal data class FluidRenderState(
    val quality: FluidQuality = FluidQuality.STANDARD,
    val playing: Boolean = false,
)

/** 用户选的 OFF/STATIC 最强；reduceMotion 退到 STATIC。低电量语义已废弃（P0-7）。 */
internal fun unifiedFluidQuality(quality:FluidQuality,reduce:Boolean):FluidQuality=when {
    quality==FluidQuality.OFF || quality==FluidQuality.STATIC->quality
    reduce->FluidQuality.STATIC
    else->quality
}

/** 背景帧预算（P0-7）：设置页显式双档，默认 60，由用户实测后拍板。 */
object BackgroundFrameRate {
    const val KEY = "bg_fps"
    const val DEFAULT = 60
    @Volatile var fps: Int = DEFAULT

    fun sanitize(value: Int): Int = if (value == 30) 30 else 60
}
