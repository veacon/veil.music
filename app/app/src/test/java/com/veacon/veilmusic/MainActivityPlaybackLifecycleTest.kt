package com.veacon.veilmusic

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelLazy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.exoplayer.ExoPlayer
import com.veacon.veilmusic.data.di.NetworkModule
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.ui.viewmodel.MusicVideoViewModel
import com.veacon.veilmusic.ui.viewmodel.MvUiState
import com.veacon.veilmusic.ui.viewmodel.PlaybackStateWriter
import com.veacon.veilmusic.ui.viewmodel.PlayerState
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import com.veacon.veilmusic.ui.viewmodel.SourcePreparation
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 离开应用（onStop）必须暂停所有由 Compose UI 持有的播放器 —— 即使 Activity 自己的 lazy 引用
 * 从未被触碰 —— 但**不能**抹掉下次启动用来自动续播的「本来在播」意图。
 *
 * 「后台播放」设置项已在 2.2.2 删除：离开应用一律暂停，回到应用一律自动续播；
 * 只有用户**主动**暂停（播放键 / 暂停键）才不续播。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class, manifest = Config.NONE)
class MainActivityPlaybackLifecycleTest {
    private class Engine {
        var pauseCalls = 0
        var playing = true
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "pause" -> { pauseCalls++; playing = false; null }
                // saveState() reads the position when it takes a bookmark.
                "getCurrentPosition" -> 4_321L
                else -> throw AssertionError("Unexpected call ${method.name}")
            }
        } as ExoPlayer
    }

    private fun <T> allocate(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    private fun field(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private inner class Fixture(allowed: Boolean = true, playing: Boolean = false) {
        val main = Engine()
        val crossfade = Engine()
        val video = Engine()
        val preparation = SourcePreparation()
        val song = Song("a", "A", "Artist", "Album", 240000, null, null, null)
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("main-activity-test", 0).apply { edit().clear().commit() }
        val writer = PlaybackStateWriter(prefs, NetworkModule.provideMoshi())
        val audioVm = allocate(PlayerViewModel::class.java).also {
            field(it, "player", main.player)
            field(it, "crossfadeExo", crossfade.player)
            field(it, "_state", MutableStateFlow(PlayerState(currentSong = song, isPlaying = playing)))
            field(it, "sourcePreparation", preparation)
            field(it, "prefs", prefs)
            field(it, "bookmarkWriterDelegate", lazy { writer })
        }
        val videoVm = allocate(MusicVideoViewModel::class.java).also {
            field(it, "player", video.player)
            field(it, "_state", MutableStateFlow(MvUiState(playing = true)))
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(
                when (modelClass) {
                    PlayerViewModel::class.java -> audioVm
                    MusicVideoViewModel::class.java -> videoVm
                    else -> throw AssertionError("Unexpected ViewModel $modelClass")
                }
            )
        }
        // Like hiltViewModel() in AppShell, populate the shared store without touching
        // either Activity delegate. Use the real ViewModelLazy / ViewModelProvider APIs.
        val audioLazy = ViewModelLazy(PlayerViewModel::class, { store }, { factory })
        val videoLazy = ViewModelLazy(MusicVideoViewModel::class, { store }, { factory })
        init {
            val uiProvider = ViewModelProvider(store, factory)
            assertSame(audioVm, uiProvider[PlayerViewModel::class.java])
            assertSame(videoVm, uiProvider[MusicVideoViewModel::class.java])
            field(activity, "playerVmLazy", audioLazy)
            field(activity, "mvVmLazy", videoLazy)
            field(activity, "appWindowAllowed", allowed)
            assertFalse(audioLazy.isInitialized())
            assertFalse(videoLazy.isInitialized())
        }
        fun stop() {
            MainActivity::class.java.getDeclaredMethod("onStop").apply { isAccessible = true }.invoke(activity)
        }
        /** 等待书签写入落盘：close() 后 worker 仍会排空队列里剩下的一条。 */
        fun drain() = runBlocking { writer.close(); writer.worker.join() }
    }

    @Test fun leavingTheAppPausesEveryUiOwnedPlayer() {
        val f = Fixture()
        f.stop()
        assertEquals(1, f.main.pauseCalls)
        assertEquals(1, f.crossfade.pauseCalls)
        assertEquals(1, f.video.pauseCalls)
        assertSame(f.audioVm, f.audioLazy.value)
        assertSame(f.videoVm, f.videoLazy.value)
        f.drain()
    }

    @Test fun disallowedWindowDoesNotCreatePlaybackViewModels() {
        val f = Fixture(allowed = false)
        f.stop()
        assertFalse(f.audioLazy.isInitialized())
        assertFalse(f.videoLazy.isInitialized())
        assertEquals(0, f.main.pauseCalls)
        f.drain()
    }

    @Test fun initializedActivityAndRepeatedStopRemainPaused() {
        val f = Fixture()
        f.audioLazy.value
        f.videoLazy.value
        f.stop()
        f.stop()
        assertFalse(f.main.playing)
        assertFalse(f.crossfade.playing)
        assertEquals(2, f.video.pauseCalls)
        f.drain()
    }

    @Test fun goingToBackgroundAlsoCancelsPendingSourceAutoplayIntent() {
        val f = Fixture()
        f.preparation.begin(7, true)
        f.stop()
        assertEquals(false, f.preparation.finish(7))
        assertFalse(f.main.playing)
        f.drain()
    }

    /** 核心回归：离开应用时暂停，但书签必须记住「本来在播」，否则下次打开不会自动续播。 */
    @Test fun leavingTheAppKeepsTheResumeIntentForTheNextLaunch() {
        val f = Fixture(playing = true)
        f.stop()
        f.drain()
        assertTrue("Leaving the app must keep was_playing", f.prefs.getBoolean("was_playing", false))
        assertEquals(4_321L, f.prefs.getLong("position_ms", -1))
    }

    /** 对照：用户主动暂停时，书签必须写「暂停」，下次打开只恢复不自动播。 */
    @Test fun anExplicitUserPauseClearsTheResumeIntent() {
        val f = Fixture(playing = true)
        f.audioVm.pause()
        f.drain()
        assertFalse("An explicit pause must not auto-resume", f.prefs.getBoolean("was_playing", true))
    }
}
