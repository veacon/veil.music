package com.veacon.veilmusic.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 歌词动画偏好：默认开启（保持旧观感），改动可持久化并被播放页读到。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LyricsMotionPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("lyrics_motion_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsToWordByWordSoExistingLookIsUnchanged() {
        val prefs = LyricsMotionPreferences(context)
        assertTrue("默认应开启逐字，避免升级后观感变化", prefs.isWordByWordEnabled())
        assertTrue(prefs.wordByWord.value)
    }

    @Test fun togglePersistsAndEmits() {
        val prefs = LyricsMotionPreferences(context)
        prefs.setWordByWord(false)
        assertFalse(prefs.isWordByWordEnabled())
        assertFalse(prefs.wordByWord.value)
        // 新实例（模拟下次启动）仍读到关闭
        assertFalse(LyricsMotionPreferences(context).isWordByWordEnabled())
        prefs.setWordByWord(true)
        assertTrue(LyricsMotionPreferences(context).isWordByWordEnabled())
    }
}
