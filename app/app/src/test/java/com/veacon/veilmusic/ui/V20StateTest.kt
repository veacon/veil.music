package com.veacon.veilmusic.ui

import android.app.Application
import com.veacon.veilmusic.data.MutPreferences
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.ui.viewmodel.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class V20StateTest {
    @Test fun onlyVerifiedCurrentAccountTriggersOneWarmupPerAuthorization() {
        val prefs = MutPreferences(RuntimeEnvironment.getApplication())
        prefs.setMUT("test-account-a")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("wrong-account")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-a")
        val revision = prefs.authorizationRevision.value
        assertTrue(revision > 0)
        repeat(3) { prefs.markVerified("test-account-a") }
        assertEquals(revision, prefs.authorizationRevision.value)
        prefs.setMUT("test-account-b")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-a")
        assertEquals(0L, prefs.authorizationRevision.value)
        prefs.markVerified("test-account-b")
        assertTrue(prefs.authorizationRevision.value > revision)
    }

    private fun track() = Song(id="real-track", title="真实歌曲", artistName="艺人", albumName="专辑",
        durationMs=100_000L, artworkUrl=null, artworkBgColor=null, previewUrl=null)

    @Test fun realSongTakesOverQueueWithoutAPlaceholderEntry() {
        val song = track()
        val ready = PlayerState(currentSong=song, queue=listOf(song), isLoading=false, isPlaying=true)
        assertEquals(1, ready.queue.size)
        assertEquals("real-track", ready.currentSong?.id)
        assertTrue(ready.isPlaying)
    }
}
