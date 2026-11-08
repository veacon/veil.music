package com.veacon.veilmusic.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放缓存的键与「该不该缓存」判定。
 *
 * 键必须稳定：直链带签名，每次解析都不同，只有 songId + 容器后缀不变；
 * 又必须区分音质：同一首歌的 FLAC 与 MP3 不能互相命中。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackCacheKeyTest {

    @Test fun keyIsStableAcrossSignedUrls() {
        val a = PlaybackCache.keyFor("536009642", "https://cdn.example.com/a/b.flac?token=1&exp=2")
        val b = PlaybackCache.keyFor("536009642", "https://other.example.net/x/y.flac?token=999")
        assertEquals(a, b)
        assertEquals("536009642@flac", a)
    }

    @Test fun keySeparatesQualities() {
        val flac = PlaybackCache.keyFor("42", "https://c/1.flac")
        val mp3 = PlaybackCache.keyFor("42", "https://c/1.mp3")
        assertFalse(flac == mp3)
    }

    @Test fun keyFallsBackWhenNoUsableExtension() {
        assertEquals("7@stream", PlaybackCache.keyFor("7", "https://c/api/stream?id=7"))
        assertEquals("8@stream", PlaybackCache.keyFor("8", "https://c/noext"))
        // 过长的「后缀」不是容器名，别当成 key 的一部分。
        assertEquals("9@stream", PlaybackCache.keyFor("9", "https://c/file.averylongextension"))
    }

    @Test fun onlyRemoteHttpIsCached() {
        assertTrue(PlaybackCache.shouldCache("https://is1-ssl.mzstatic.com/a.flac"))
        assertTrue(PlaybackCache.shouldCache("http://cdn.example.com/a.mp3"))
        // 本地代理是本机数据，缓存只会白占空间。
        assertFalse(PlaybackCache.shouldCache("http://127.0.0.1:8080/api/stream/1"))
        assertFalse(PlaybackCache.shouldCache("http://localhost:8080/api/stream/1"))
        assertFalse(PlaybackCache.shouldCache("http://10.0.2.2:3000/api/stream/1"))
        assertFalse(PlaybackCache.shouldCache("file:///sdcard/a.flac"))
    }
}
