package com.veil.music.util

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 让 Composable 直接拿到单例 [UpdateManager]。
 *
 * 更新状态不属于任何 ViewModel —— 它要活过设置页、活过整轮下载，所以用 Hilt 的
 * EntryPoint 从 SingletonComponent 里取，启动检查与设置页共用同一份状态。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun updateManager(): UpdateManager
}
