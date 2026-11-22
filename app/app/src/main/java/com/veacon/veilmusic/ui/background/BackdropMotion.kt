package com.veacon.veilmusic.ui.background

import com.veacon.veilmusic.media.AudioSpectrum
import kotlin.math.exp

/** Frame-rate-independent smoothing for measured audio, separate from the ambient orbit clock. */
internal class BackdropMotion {
    var elapsedNanos: Long = 0L
        private set
    var bass: Float = 0f
        private set
    var bassImpact: Float = 0f
        private set
    var mid: Float = 0f
        private set
    var treble: Float = 0f
        private set
    /** 脉动速度倍率：只缩放环境时钟推进，不改变音频包络响应。 */
    @Volatile var speedMultiplier: Float = 1f
    /** 呼吸幅度固定「弱」：只缩放包络输出，不反馈进检测，避免整屏提亮/爆闪。 */
    private val amplitude = 0.8f
    /** 鼓点检测灵敏度固定「强」：只作用于检测门槛，不改输出包络。 */
    private val beatSensitivity = 1.4f
    private var bassFloor = 0f
    private var bassImpulse = 0f
    private var hasBassBaseline = false

    fun advance(deltaNanos: Long, spectrum: AudioSpectrum) {
        // Neither a stale frame callback nor a resumed lifecycle should jump the color field.
        val delta = deltaNanos.coerceIn(0L, 64_000_000L)
        if (delta == 0L) return
        elapsedNanos += (delta * speedMultiplier.coerceIn(0.1f, 4f)).toLong()
        val seconds = delta / 1_000_000_000f
        val bassInput = target(spectrum.bass, spectrum.available)
        // The logarithmic FFT level often sits around 0.6–0.8 throughout a song. Scaling
        // that almost-constant level only makes a permanently wider blob. Detect rises
        // above the recent bass floor instead; no clock, lyrics or synthetic beats.
        val validCapture = spectrum.captured && spectrum.bass.isFinite()
        val impactTarget = if (!validCapture) {
            // Reconnecting a capture in the middle of a sustained note is not a kick.
            hasBassBaseline = false
            bassFloor = 0f
            0f
        } else if (!hasBassBaseline) {
            bassFloor = bassInput
            hasBassBaseline = true
            0f
        } else {
            bassFloor += (bassInput - bassFloor) * (1f - exp(-seconds / 0.65f))
            // 灵敏度只调检测门槛：高灵敏降低 rise 死区与电平下限，低灵敏提高；
            // 触发后的输出包络与检测解耦，所以再灵敏也不会放大成整屏提亮/爆闪。
            val sensitivity = beatSensitivity.coerceIn(0.5f, 2f)
            val deadZone = 0.025f / sensitivity
            val gate = (0.14f / sensitivity).coerceIn(0.06f, 0.3f)
            val half = (0.06f / sensitivity).coerceIn(0.025f, 0.12f)
            val rise = (bassInput - bassFloor - deadZone).coerceAtLeast(0f)
            if (spectrum.available && bassInput >= gate) rise / (half + rise) else 0f
        }
        // Two short envelopes keep the silhouette continuous at a kick's attack, while
        // letting it return between kicks. This is separate from sustained bass pressure.
        bassImpulse = smooth(bassImpulse, impactTarget, seconds, 0.025f, 0.10f)
        // 呼吸幅度只缩放输出包络（不反馈进检测），保持原版底色块几何与配色。
        val depth = amplitude.coerceIn(0.5f, 2.5f)
        bassImpact = (smooth(bassImpact, bassImpulse, seconds, 0.045f, 0.17f) * depth).coerceIn(0f, 1f)
        // A fluid silhouette needs a soft attack/longer release, rather than strobing on FFT bins.
        bass = (smooth(bass, bassInput, seconds, 0.09f, 0.24f) * depth).coerceIn(0f, 1f)
        mid = smooth(mid, target(spectrum.mid, spectrum.available), seconds, 0.07f, 0.20f)
        // Treble is an accent: soften fast hats/sibilants without delaying the bass impulse.
        treble = smooth(treble, target(spectrum.treble, spectrum.available), seconds, 0.11f, 0.25f)
    }

    private fun target(value: Float, available: Boolean): Float =
        if (available && value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun smooth(current: Float, target: Float, seconds: Float, attack: Float, release: Float): Float {
        val timeConstant = if (target > current) attack else release
        val next = target + (current - target) * exp(-seconds / timeConstant)
        return if (next < 0.001f && target == 0f) 0f else next.coerceIn(0f, 1f)
    }
}
