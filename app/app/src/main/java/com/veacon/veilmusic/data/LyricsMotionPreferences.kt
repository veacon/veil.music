package com.veacon.veilmusic.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词动画偏好。
 *
 * 两个开关：
 * - **逐字播放**（默认开）：当前行按时间逐字（中文）／逐词（西文）点亮，关闭则整句直接亮起；
 * - **歌词动效**（默认关）：开启卡拉OK渲染——逐字遮罩羽化扫过、长音字符轻弹上浮、
 *   当前行平滑放大而上下行收起（对齐 am_atv / Apple Music）。关闭时保持原来的逐字颜色渐变。
 *
 * 都只影响高亮动画，不影响歌词来源、字号、行列布局与滚动。默认值保持升级前观感不变。
 */
@Singleton
class LyricsMotionPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_motion_prefs", Context.MODE_PRIVATE)

    private val _wordByWord = MutableStateFlow(prefs.getBoolean(KEY_WORD_BY_WORD, true))
    /** 播放页直接 collect，改设置后无需重进页面。 */
    val wordByWord: StateFlow<Boolean> = _wordByWord

    fun isWordByWordEnabled(): Boolean = _wordByWord.value

    fun setWordByWord(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WORD_BY_WORD, enabled) }
        _wordByWord.value = enabled
    }

    /** 歌词动效：遮罩扫过 + 长音字符弹起 + 当前行挤压（对齐 am_atv / Apple Music）。默认关闭。 */
    private val _elastic = MutableStateFlow(prefs.getBoolean(KEY_ELASTIC, false))
    val elastic: StateFlow<Boolean> = _elastic

    fun isElasticEnabled(): Boolean = _elastic.value

    fun setElastic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ELASTIC, enabled) }
        _elastic.value = enabled
    }

    companion object {
        const val KEY_WORD_BY_WORD = "word_by_word"
        const val KEY_ELASTIC = "lyric_elastic"
    }
}
