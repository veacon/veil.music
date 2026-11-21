package com.veacon.veilmusic.ui.background

/** 背景脉动速度六档倍率，流体与熔岩共享。 */
object LavaPulseSpeed {
    val STEPS = floatArrayOf(0.3f, 0.5f, 1f, 1.5f, 2f, 2.5f)

    fun multiplier(index: Int): Float = STEPS.getOrElse(index) { 1f }

    fun label(index: Int): String {
        val m = multiplier(index)
        return if (m == 1f) "×1" else "×$m"
    }
}
