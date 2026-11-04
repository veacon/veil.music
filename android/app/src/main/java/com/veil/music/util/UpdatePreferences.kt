package com.veil.music.util

import android.content.Context

/** Whether the self-updater should also offer prerelease ("beta") builds. */
object UpdatePreferences {
    private const val PREFS = "update_prefs"
    private const val KEY_BETA = "beta"
    private const val KEY_PROXY = "github_proxy"

    fun betaEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA, false)

    fun setBeta(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA, on).apply()
    }

    /**
     * 检查更新 / 下载 APK 时首选的 GitHub 线路。
     *
     * 电视连不上 GitHub，这条记录决定「先试哪条」—— 4 条加速站或「直连」。没选过就用
     * 默认那条加速站。
     */
    fun githubProxy(context: Context): GithubProxy =
        GithubProxy.fromName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROXY, null),
        )

    fun setGithubProxy(context: Context, proxy: GithubProxy) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROXY, proxy.name).apply()
    }
}
