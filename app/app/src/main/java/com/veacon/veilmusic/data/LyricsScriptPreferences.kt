package com.veacon.veilmusic.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** 歌词字形：简体 / 繁体。 */
enum class LyricsScript {
    SIMPLIFIED,
    TRADITIONAL,
}

/**
 * 歌词字形偏好。
 *
 * 背景：Apple 曲库（尤其是 cn 区）返回的 `syllable-lyrics` / `lyrics` 大量是**繁体**，
 * 而九成用户习惯简体，此前只能手动无视。这里给一个开关，命中后统一转换字形。
 *
 * 默认简体：既修复「Apple 源歌词是繁体」的反馈，又对本来就简体的第三方歌词是**空操作**
 * （逐字转换只在字形确实需要变化时才改动字符），不会影响英文/日文歌词。
 */
@Singleton
class LyricsScriptPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("lyrics_script_prefs", Context.MODE_PRIVATE)

    private val _script = MutableStateFlow(current())
    /** 设置页改、播放页即时生效（歌词缓存会被清掉重取）。 */
    val script: StateFlow<LyricsScript> = _script

    fun current(): LyricsScript =
        LyricsScript.entries.firstOrNull { it.name == prefs.getString(KEY_SCRIPT, null) }
            ?: LyricsScript.SIMPLIFIED

    fun setScript(script: LyricsScript) {
        prefs.edit { putString(KEY_SCRIPT, script.name) }
        _script.value = script
    }

    companion object {
        const val KEY_SCRIPT = "script"
    }
}
