package com.veacon.veilmusic.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 跨 ViewModel 的「重新拉取」信号（设置页 → 首页）。
 *
 * 账号刷新后云端歌单会变，首页推荐也需要跟着重取；HomeViewModel 与设置页不在同一
 * NavBackStackEntry，无法直接互相调用，这里用一个极轻量的 SharedFlow 广播。
 */
object AppRefresh {
    private val _recommendations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recommendations: SharedFlow<Unit> = _recommendations

    fun requestRecommendations() {
        _recommendations.tryEmit(Unit)
    }
}
