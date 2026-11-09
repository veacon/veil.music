package com.veacon.veilmusic.data.updates

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateDownloadTest {

    @Test fun eightChunksCoverEveryByteExactlyOnceWithoutGaps() {
        val size = 19_502_575L
        val ranges = chunkRanges(size, 8)
        assertEquals(8, ranges.size)
        assertEquals(0L, ranges.first().first)
        assertEquals(size - 1, ranges.last().last)
        for (i in 1 until ranges.size) {
            assertEquals("gap/overlap between chunk ${i - 1} and $i",
                ranges[i - 1].last + 1, ranges[i].first)
        }
        assertEquals(size, ranges.sumOf { it.last - it.first + 1 })
    }

    @Test fun fileSmallerThanThreadCountUsesAtMostOneRangePerByte() {
        val ranges = chunkRanges(3L, 8)
        assertEquals(3, ranges.size)
        assertEquals(0L..0L, ranges[0])
        assertEquals(1L..1L, ranges[1])
        assertEquals(2L..2L, ranges[2])
    }

    @Test fun exactlyThreeMirrorsPlusOneDirectSource() {
        val sources = DownloadSource.entries
        assertEquals(4, sources.size)
        assertEquals(1, sources.count { it.prefix.isEmpty() })
        assertEquals(3, sources.count { it.prefix.startsWith("https://") && it.prefix.endsWith("/") })
    }

    @Test fun directKeepsUrlAndMirrorsWrapIt() {
        val url = "https://github.com/veacon/veil.music/releases/download/v2.1/Veil-Music-2.1.apk"
        assertEquals(url, DownloadSource.DIRECT.url(url))
        for (s in DownloadSource.entries.filter { it != DownloadSource.DIRECT }) {
            assertEquals(s.prefix + url, s.url(url))
        }
    }

    @Test fun unknownStoredKeyFallsBackToDirect() {
        assertEquals(DownloadSource.DIRECT, DownloadSource.fromKey(null))
        assertEquals(DownloadSource.DIRECT, DownloadSource.fromKey("evil.example.com"))
        assertEquals(DownloadSource.GHFAST, DownloadSource.fromKey("ghfast"))
    }

    @Test fun selectedSourceSurvivesANewPreferenceInstance() {
        val context = RuntimeEnvironment.getApplication()
        val first = UpdateDownloadPreferences(context)
        assertEquals(DownloadSource.DIRECT, first.current())
        first.select(DownloadSource.GHPROXY_NET)
        assertEquals(DownloadSource.GHPROXY_NET, UpdateDownloadPreferences(context).current())
    }
}
