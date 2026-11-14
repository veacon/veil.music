package com.veacon.veilmusic.data

import android.content.Context
import com.veacon.veilmusic.ui.theme.AppPalette
import java.util.Calendar

/**
 * 按时间自动切换深浅色（设置页 → 界面设置 → 定时切换深浅色）。
 *
 * - 时间粒度 **30 分钟**：存的是「当天第几分钟」（0..1439，且对齐到 30 的整数倍）；
 * - 深色时段**跨零点也正确**：例如 19:30 → 07:00 表示 19:30～次日 07:00；
 * - 起止相同视为「不切」，不会莫名其妙常驻深色；
 * - 只在开启时接管主题；关掉后完全交回用户在「界面模式」里手动选的值；
 * - 复用 `appearance` 这一份 prefs（与手动深浅色的 `dark` 同源），不新增存储。
 */
object AppearanceSchedule {
    private const val PREFS = "appearance"
    private const val KEY_ENABLED = "auto_dark_enabled"
    private const val KEY_START_MIN = "auto_dark_start_min"
    private const val KEY_END_MIN = "auto_dark_end_min"

    /** 时间粒度：半小时。 */
    const val SLOT_MINUTES = 30

    const val DEFAULT_START_MIN = 19 * 60 + 30   // 19:30
    const val DEFAULT_END_MIN = 7 * 60           // 07:00

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun snap(minute: Int): Int {
        val clamped = minute.coerceIn(0, 24 * 60 - SLOT_MINUTES)
        return (clamped / SLOT_MINUTES) * SLOT_MINUTES
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun startMin(context: Context): Int = snap(prefs(context).getInt(KEY_START_MIN, DEFAULT_START_MIN))

    fun setStartMin(context: Context, minute: Int) {
        prefs(context).edit().putInt(KEY_START_MIN, snap(minute)).apply()
    }

    fun endMin(context: Context): Int = snap(prefs(context).getInt(KEY_END_MIN, DEFAULT_END_MIN))

    fun setEndMin(context: Context, minute: Int) {
        prefs(context).edit().putInt(KEY_END_MIN, snap(minute)).apply()
    }

    /** 步进一格（±30 分钟），自动绕回 00:00。 */
    fun step(minute: Int, delta: Int): Int {
        val slots = 24 * 60 / SLOT_MINUTES
        val current = snap(minute) / SLOT_MINUTES
        return (((current + delta) % slots + slots) % slots) * SLOT_MINUTES
    }

    /** 展示用：`19:30`、`07:00`。 */
    fun label(minute: Int): String {
        val m = snap(minute)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** 纯函数：给定「当天第几分钟」与深色时段起止，判断此刻是否应是深色。 */
    fun shouldBeDark(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end   // 跨零点
    }

    /**
     * 开启时按当前时间把主题应用到 [AppPalette]。
     * @return true 表示本次真的改动了主题（没变就不动，避免多余重组）。
     */
    fun apply(context: Context, nowMin: Int = nowMinuteOfDay()): Boolean {
        if (!enabled(context)) return false
        val want = shouldBeDark(nowMin, startMin(context), endMin(context))
        if (AppPalette.isDark == want) return false
        AppPalette.setDark(context, want)
        return true
    }

    private fun nowMinuteOfDay(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }
}
