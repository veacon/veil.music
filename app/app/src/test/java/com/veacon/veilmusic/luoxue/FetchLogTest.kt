package com.veacon.veilmusic.luoxue

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json on android.jar is a stub outside Robolectric; the runner supplies a real implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FetchLogTest {
    @Test fun lineCarriesTimestampElapsedSongIdentityAndOutcome() {
        val line = FetchLog.line(
            songId = "song-42", title = "晴天", artist = "周杰伦", album = "叶惠美",
            startMs = 1_731_141_000_123L, elapsedMs = 842L, result = "ok",
            source = "wy", quality = "flac24bit", kbps = 999, script = "lx-01-kulao.js", attempts = 3,
        )
        assertTrue(line.startsWith("FETCH "))
        val json = JSONObject(line.removePrefix("FETCH "))
        assertEquals("fetch", json.getString("event"))
        assertTrue("ts must be ISO-8601 UTC: ${json.getString("ts")}",
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""").matches(json.getString("ts")))
        assertEquals(1_731_141_000_123L, json.getLong("start_ms"))
        assertEquals(842L, json.getLong("elapsed_ms"))
        assertEquals("song-42", json.getString("song_id"))
        assertEquals("晴天", json.getString("title"))
        assertEquals("周杰伦", json.getString("artist"))
        assertEquals("叶惠美", json.getString("album"))
        assertEquals("ok", json.getString("result"))
        assertEquals("wy", json.getString("source"))
        assertEquals("flac24bit", json.getString("quality"))
        assertEquals(999, json.getInt("kbps"))
        assertEquals("lx-01-kulao.js", json.getString("script"))
        assertEquals(3, json.getInt("attempts"))
    }

    @Test fun failureAndQuotedTitlesStillProduceOneParseableLine() {
        val line = FetchLog.line(
            songId = "id|1", title = "Say \"Hi\"\nAgain", artist = "A", album = "B",
            startMs = 0L, elapsedMs = 12L, result = "miss", reason = "engine not ready",
        )
        val json = JSONObject(line.removePrefix("FETCH "))
        assertEquals("miss", json.getString("result"))
        assertEquals("engine not ready", json.getString("reason"))
        assertEquals("Say \"Hi\"\nAgain", json.getString("title"))
        assertEquals("id|1", json.getString("song_id"))
    }
}
