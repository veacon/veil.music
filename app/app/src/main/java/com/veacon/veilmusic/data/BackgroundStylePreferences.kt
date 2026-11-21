package com.veacon.veilmusic.data

import android.content.Context

/** 播放页背景风格：流体（主线 Mac 流体）/ 熔岩。 */
object BackgroundStylePreferences {
    const val FLUID = "fluid"
    const val LAVA = "lava"
    private const val PREFS = "background_style"
    private const val KEY = "style"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, FLUID) ?: FLUID

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).apply()
    }
}
