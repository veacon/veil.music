package com.veacon.veilmusic.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 展示用令牌模块：默认关闭、关闭时零请求、独立存储、播放端点硬隔离。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppleDisplayAuthTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        AppleDisplayAuth.get(context).setEnabled(false)
        context.getSharedPreferences("apple_display_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("apple_music_tv", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultIsOffAndExposesNoAccountState() {
        val auth = AppleDisplayAuth.get(context)
        assertFalse(auth.isEnabled())
        assertEquals(AppleDisplayAuth.State(), auth.state.value)
        assertFalse(auth.hasToken())
    }

    @Test fun signInWhileDisabledIsANoOpThatNeverTouchesThePlaybackTokenStore() {
        val auth = AppleDisplayAuth.get(context)
        assertEquals("disabled", auth.signIn("a-valid-looking-display-token-1234567890"))
        assertFalse(auth.hasToken())
        assertEquals("", MutPreferences(context).getMUT())
        assertEquals(AppleDisplayAuth.State(), auth.state.value)
    }

    @Test fun turningTheSwitchOffClearsDisplayStateWithoutTouchingStoredToken() {
        val prefs = context.getSharedPreferences("apple_display_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putString("mut", "display-token")
            .putString("storefront", "cn").putString("account_name", "Someone").commit()
        // A fresh instance reads the cached state; turning it off must blank out everything visible.
        val auth = AppleDisplayAuth.get(context)
        auth.setEnabled(false)
        assertFalse(auth.isEnabled())
        assertEquals(AppleDisplayAuth.State(), auth.state.value)
        assertTrue("the stored token is kept for the next enable, but never surfaced while off",
            prefs.getString("mut", "") == "display-token")
        assertEquals("", MutPreferences(context).getMUT())
    }

    @Test fun onlyDisplayEndpointsPassTheGuard_playbackDataNeverReachesAppleMusic() {
        listOf(
            "https://music.apple.com/",
            "https://music.apple.com/assets/index.abc123.js",
            "https://api.music.apple.com/v1/me/storefront",
            "https://api.music.apple.com/v1/me/account?meta=subscription",
            "https://api.music.apple.com/v1/me/library/playlists?limit=6",
        ).forEach { assertTrue("$it should be allowed", AppleDisplayAuth.isDisplayOnlyUrl(it)) }

        listOf(
            "https://play.music.apple.com/WebObjects/MZPlay.woa/wa/subVideoPlaybackInfo",
            "https://amp-api-edge.music.apple.com/v1/catalog/us/songs/123?include=stream",
            "https://api.music.apple.com/v1/me/storefront?include=hls-url",
            "https://api.music.apple.com/v1/me/library/songs/123/streamUrl",
            "https://api.music.apple.com/v1/me/account?include=drm",
            "https://example.com/v1/me/storefront",
            "http://api.music.apple.com/v1/me/storefront",
        ).forEach { assertFalse("$it must be blocked", AppleDisplayAuth.isDisplayOnlyUrl(it)) }
    }
}
