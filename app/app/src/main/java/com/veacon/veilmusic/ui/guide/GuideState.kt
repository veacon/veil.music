package com.veacon.veilmusic.ui.guide

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 新手引导页（= 发现页）的持久化状态。
 *
 * - **首次安装 / 版本升级后的第一次启动**自动弹出：[completedVersion] 与
 *   [GUIDE_VERSION] 不一致即视为未完成 —— 升级带来新页面时用户会再看一次；
 * - 用户点「完成」后写入当前版本号，之后不再自动弹出；
 * - 独立于旧引导（`veil_guide`）与首启设置流程（`onboarding_prefs`）的存储；
 * - **开发者选项**提供入口开关 [autoShow] / [setAutoShow] 与 [requestReplay]（清完成标记并
 *   立刻重新弹出），方便反复测试而不用清应用数据。
 */
object GuideState {
    private const val PREFS = "guide_prefs"
    private const val KEY_COMPLETED = "completed_version"
    private const val KEY_AUTO_SHOW = "auto_show"

    /** 引导页结构版本：内容或页数变化时 +1，升级用户即可再看一次。 */
    const val GUIDE_VERSION = 1

    /**
     * 进程内的「重新观看」信号：[requestReplay] 时自增，AppShell 收到后立刻重新弹出引导页。
     * 放进单例而不是层层传回调 —— 开发者选项在 NavHost 的独立作用域里，拿不到 AppShell 的状态。
     */
    val replayRequests = MutableStateFlow(0L)

    /** 开发者选项里的「自动弹出新手引导」开关；关掉后不再自动弹出。 */
    fun autoShow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SHOW, true)

    fun setAutoShow(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SHOW, on).apply()
    }

    fun completed(context: Context): Boolean =
        !autoShow(context) || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COMPLETED, 0) >= GUIDE_VERSION

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPLETED, GUIDE_VERSION).apply()
    }

    /** 清掉完成标记：下次启动会重新走一遍引导。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPLETED, 0).apply()
    }

    /** 开发者选项：「重新观看新手引导」——清完成标记并立刻重新弹出。 */
    fun requestReplay(context: Context) {
        reset(context)
        replayRequests.value += 1
    }
}
