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
 * 目前只有一个开关：**逐字播放**。开启时当前行按时间逐字（中文）／逐词（西文）点亮，
 * 关闭后当前行整句直接亮起 —— 只影响高亮动画，不影响歌词来源、字号、行列布局与滚动。
 *
 * 默认开启：保持设置项出现之前的表现，避免升级后观感突然变化。
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

    companion object {
        const val KEY_WORD_BY_WORD = "word_by_word"
    }
}
