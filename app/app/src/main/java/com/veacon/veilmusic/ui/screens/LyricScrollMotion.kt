package com.veacon.veilmusic.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

internal const val LYRIC_SCROLL_DURATION_MS = 550
private const val RETARGET_WINDOW_MS = 150L

/** A soft S-curve; retain forward velocity when a new line interrupts the previous move. */
internal fun lyricScrollEasing(distance: Float, velocity: Float): CubicBezierEasing {
    val forwardVelocity = if (distance * velocity > 0f) abs(velocity) else 0f
    val firstY = if (abs(distance) > 0.001f)
        (forwardVelocity * (LYRIC_SCROLL_DURATION_MS / 1000f) / abs(distance) / 3f).coerceIn(0f, 1f)
    else 0f
    return CubicBezierEasing(1f / 3f, firstY, 2f / 3f, 1f)
}

/**
 * Remember only the most recent applied velocity; never carry it across user or long jumps.
 *
 * [elastic] 开启时使用 am_atv 的 staggered FLIP 滚动手感：CubicBezier(0.4,0.1,0,1)、750ms，
 * 让切行滚动更平滑有层次；关闭时保持原有的速度继承 S 曲线。
 */
internal class LyricScrollMotion(private val elastic: Boolean = false) {
    private var lastVelocity = 0f
    private var lastFrameMs = Long.MIN_VALUE

    fun reset() { lastVelocity = 0f; lastFrameMs = Long.MIN_VALUE }

    /** 弹性开启时切行滚动统一走这里；用同一条 am_atv 曲线，避免 animateScrollToItem 的默认 spring 大跳。 */
    val preferElasticScrollToIndex: Boolean get() = elastic

    suspend fun scrollToIndex(state: LazyListState, index: Int) {
        if (!elastic) {
            state.animateScrollToItem(index)
            return
        }
        // 该版本 animateScrollToItem 不接受自定义 animationSpec（默认 spring 是"瞬跳感"来源）。
        // 做法：先把视口无动画 snap 到"目标行刚好露头"的邻近位置，再用弹性 scrollBy 走完最后一段，
        // 于是大跨度回归也呈现 am_atv 那种平滑上浮，而不是 spring 大跳。
        reset()
        val snapTo = (index - 2).coerceAtLeast(0)
        state.scrollToItem(snapTo)
        val info = state.layoutInfo
        val targetItem = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (targetItem != null) {
            val viewportH = info.viewportEndOffset - info.viewportStartOffset
            val delta = (targetItem.offset + targetItem.size / 2f) - viewportH * 0.30f
            if (kotlin.math.abs(delta) > 1f) scrollBy(state, delta)
        }
    }

    suspend fun scrollBy(state: LazyListState, distance: Float) {
        val elapsed = SystemClock.uptimeMillis() - lastFrameMs
        val inherited = if (elapsed in 0..RETARGET_WINDOW_MS) lastVelocity else 0f
        val spec: AnimationSpec<Float> = if (elastic) {
            tween(LyricElastic.LINE_SCROLL_DURATION_MS, easing = LyricElastic.LINE_SCROLL_EASING)
        } else {
            tween(LYRIC_SCROLL_DURATION_MS, easing = lyricScrollEasing(distance, inherited))
        }
        var previous = 0f
        var completed = false
        try {
            state.scroll {
                animate(0f, distance, animationSpec = spec) { value, velocity ->
                    val desired = value - previous
                    val consumed = scrollBy(desired)
                    previous = value
                    lastVelocity = if (abs(consumed - desired) < 0.5f) velocity else 0f
                    lastFrameMs = SystemClock.uptimeMillis()
                }
            }
            completed = true
        } finally {
            if (completed) reset()
        }
    }
}
