package com.veil.music.data

import android.content.Context
import com.veil.music.data.model.Quality
import com.veil.music.data.model.SourceID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音源相关的用户偏好。所有写入都会同时更新 StateFlow，
 * 因此「设置页改首页数据源」不需要重启，发现页会在下一次 [StateFlow] 变化时重载。
 */
@Singleton
class VeilPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("veil_prefs", Context.MODE_PRIVATE)

    private val _homeSource = MutableStateFlow(
        SourceID.fromKey(prefs.getString(KEY_HOME_SOURCE, null)) ?: DEFAULT_HOME_SOURCE
    )
    /** 首页四个数据源里当前选中的那个（单选）。 */
    val homeSource: StateFlow<SourceID> = _homeSource.asStateFlow()

    private val _playQuality = MutableStateFlow(
        Quality.fromKey(prefs.getString(KEY_PLAY_QUALITY, null)) ?: Quality.K320
    )
    val playQuality: StateFlow<Quality> = _playQuality.asStateFlow()

    private val _toggleSource = MutableStateFlow(prefs.getBoolean(KEY_TOGGLE_SOURCE, true))
    /** 解析失败时是否按「歌名+歌手+时长」自动换源（对应 lx 的 toggle source）。 */
    val toggleSource: StateFlow<Boolean> = _toggleSource.asStateFlow()

    private val _builtinFallback = MutableStateFlow(prefs.getBoolean(KEY_BUILTIN_FALLBACK, true))
    /** 脚本与换源都失败时，是否用内置直连（酷我 / 网易云）兜底。 */
    val builtinFallback: StateFlow<Boolean> = _builtinFallback.asStateFlow()

    private val _showResolveHint = MutableStateFlow(prefs.getBoolean(KEY_SHOW_HINT, true))
    /** 换源 / 降级时在播放页给一条提示。 */
    val showResolveHint: StateFlow<Boolean> = _showResolveHint.asStateFlow()

    fun setHomeSource(source: SourceID) {
        prefs.edit().putString(KEY_HOME_SOURCE, source.key).apply()
        _homeSource.value = source
    }

    fun setPlayQuality(quality: Quality) {
        prefs.edit().putString(KEY_PLAY_QUALITY, quality.key).apply()
        _playQuality.value = quality
    }

    fun setToggleSource(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOGGLE_SOURCE, enabled).apply()
        _toggleSource.value = enabled
    }

    fun setBuiltinFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUILTIN_FALLBACK, enabled).apply()
        _builtinFallback.value = enabled
    }

    fun setShowResolveHint(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HINT, enabled).apply()
        _showResolveHint.value = enabled
    }

    companion object {
        private const val KEY_HOME_SOURCE = "home_source"
        private const val KEY_PLAY_QUALITY = "play_quality"
        private const val KEY_TOGGLE_SOURCE = "toggle_source"
        private const val KEY_BUILTIN_FALLBACK = "builtin_fallback"
        private const val KEY_SHOW_HINT = "show_resolve_hint"

        val DEFAULT_HOME_SOURCE = SourceID.WY

        /** 设置页暴露的四个首页数据源，顺序与卡片一致。 */
        val HOME_SOURCES: List<SourceID> = SourceID.homePageOrder
    }
}
