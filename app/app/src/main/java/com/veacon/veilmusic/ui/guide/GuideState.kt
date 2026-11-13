package com.veacon.veilmusic.ui.guide

import android.content.Context

/**
 * 新手引导页的持久化状态。
 *
 * - **首次安装 / 版本升级后的第一次启动**自动弹出：[completedVersion] 与
 *   [GUIDE_VERSION] 不一致即视为未完成 —— 升级带来新页面时用户会再看一次；
 * - 用户点「完成」后写入当前版本号，之后不再自动弹出；
 * - 独立于旧引导（`veil_guide`）与首启设置流程（`onboarding_prefs`）的存储。
 */
object GuideState {
    private const val PREFS = "guide_prefs"
    private const val KEY_COMPLETED = "completed_version"

    /** 引导页结构版本：内容或页数变化时 +1，升级用户即可再看一次。 */
    const val GUIDE_VERSION = 1

    fun completed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COMPLETED, 0) >= GUIDE_VERSION

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPLETED, GUIDE_VERSION).apply()
    }
}
