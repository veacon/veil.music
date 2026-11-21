package com.veacon.veilmusic.ui.screens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * 歌词行切换「挤压」弹性：对齐 am_atv（Apple Music 风格）的行高亮参数。
 *
 * 关键：am_atv 的"当前行凸显/挤压"感主要来自**强烈的明暗对比 + 行缩放**，而非大位移：
 * - 当前行：alpha 0.94（KaraokePrimaryAlpha）、scale 1.0（HighlightScale）
 * - 非当前行：alpha 0.18（KaraokeTertiaryAlpha）、scale 0.98（NormalScale）
 * 之前本项目非当前行 alpha 是 0.35/0.40（太亮），导致当前行跳不出来、3% 缩放也看不出，
 * 于是"开关没区别"。这里按 am_atv 原值把非当前行压暗并缩小，开关控制。
 *
 * spring：AMLL lineScaleSpringParams mass=2/damping=25/stiffness=100 → 阻尼比 ≈ 0.88。
 */
internal object LyricElastic {
    // ── 行缩放（am_atv KaraokeLineHighlightScale / NormalScale）──
    const val LINE_SCALE_ACTIVE = 1f
    const val LINE_SCALE_INACTIVE = 0.98f
    /** dampingRatio = damping / (2·√(mass·stiffness)) = 25 / (2·√200) ≈ 0.884。 */
    const val LINE_SCALE_DAMPING_RATIO = 0.88f
    const val LINE_SCALE_STIFFNESS = 100f

    // ── 行透明度（am_atv KaraokePrimaryAlpha / TertiaryAlpha）──
    /** 当前行：0.94（略透亮的白，避免纯白刺眼）。 */
    const val ALPHA_ACTIVE = 0.94f
    /** 非当前行：0.18，压得很暗以强烈凸显当前行（这是"聚焦/挤压"感的来源）。 */
    const val ALPHA_INACTIVE = 0.18f
    /** 关闭弹性时的旧观感：已唱 0.35、未唱 0.40。 */
    const val ALPHA_PAST_LEGACY = 0.35f
    const val ALPHA_FUTURE_LEGACY = 0.40f

    /** 行缩放目标：关闭恒 1.0；开启时当前行 1.0、其余 0.98。 */
    fun lineScaleTarget(isActive: Boolean, elastic: Boolean): Float = when {
        !elastic -> LINE_SCALE_ACTIVE
        isActive -> LINE_SCALE_ACTIVE
        else -> LINE_SCALE_INACTIVE
    }

    /** 行透明度目标：开启弹性时当前行 0.94、其余压暗到 0.18；关闭保持旧的 0.35/0.40。 */
    fun lineAlphaTarget(isActive: Boolean, isPast: Boolean, elastic: Boolean): Float = when {
        !elastic -> if (isActive) 1f else if (isPast) ALPHA_PAST_LEGACY else ALPHA_FUTURE_LEGACY
        isActive -> ALPHA_ACTIVE
        else -> ALPHA_INACTIVE
    }

    // ── 切行滚动 ──
    /** am_atv KaraokeItemChangeMillis = 750。 */
    const val LINE_SCROLL_DURATION_MS = 750

    /** am_atv KaraokeLineEasing = CubicBezier(0.4, 0.1, 0, 1)。 */
    val LINE_SCROLL_EASING: Easing = CubicBezierEasing(0.4f, 0.1f, 0f, 1f)
}
