package com.veacon.veilmusic.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网易云导入的解析层回归。
 *
 * 背景（线上反馈「网易云导入歌单只能显示 6 首」）：未登录状态下 `/api/v6/playlist/detail`
 * 的 `tracks` 会被服务端截断（2024 实测固定只回前 10 首，`n=1000` 无效），而 `trackIds` 是完整的。
 * 所以导入必须按 `trackIds` 走，缺的用 `/api/v3/song/detail` 补齐 —— 这几条测试就锁这个行为。
 *
 * org.json 在 android.jar 里是桩实现，必须走 Robolectric 才有真实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NeteaseImportParsingTest {

    private fun track(id: Long, name: String, artist: String = "歌手", album: String = "专辑", pic: String = "https://p1.music.126.net/x.jpg") =
        JSONObject().apply {
            put("id", id)
            put("name", name)
            put("dt", 240_000)
            put("ar", JSONArray().put(JSONObject().put("name", artist)))
            put("al", JSONObject().put("name", album).put("picUrl", pic))
        }

    private fun playlist(trackCount: Int, inlineCount: Int, totalIds: Int): JSONObject {
        val ids = JSONArray()
        for (i in 1..totalIds) ids.put(JSONObject().put("id", 1000L + i))
        val tracks = JSONArray()
        for (i in 1..inlineCount) tracks.put(track(1000L + i, "歌曲$i"))
        return JSONObject().apply {
            put("trackCount", trackCount)
            put("trackIds", ids)
            put("tracks", tracks)
        }
    }

    @Test
    fun `trackIds 完整时全部保留_即使内嵌 tracks 被截断`() {
        val pl = playlist(trackCount = 27, inlineCount = 10, totalIds = 27)
        val ids = neteaseTrackIds(pl)
        val inline = neteaseDetailSongs(pl)
        assertEquals(27, ids.size)
        assertEquals(10, inline.size)

        val extra = (11..27).map { neteaseSong(track(1000L + it, "歌曲$it"))!! }
        val merged = mergeNeteaseSongs(ids, inline, extra)
        assertEquals("截断后必须补齐到 27 首", 27, merged.size)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
    }

    @Test
    fun `合并保持 trackIds 顺序`() {
        val ids = listOf(1001L, 1002L, 1003L)
        val inline = listOf(neteaseSong(track(1003L, "C"))!!)
        val extra = listOf(neteaseSong(track(1001L, "A"))!!, neteaseSong(track(1002L, "B"))!!)
        val merged = mergeNeteaseSongs(ids, inline, extra)
        assertEquals(listOf("imp-ne-1001", "imp-ne-1002", "imp-ne-1003"), merged.map { it.id })
        assertEquals(listOf("A", "B", "C"), merged.map { it.title })
    }

    @Test
    fun `内嵌 tracks 优先于详情接口`() {
        val ids = listOf(1001L)
        val inline = listOf(neteaseSong(track(1001L, "内嵌"))!!)
        val extra = listOf(neteaseSong(track(1001L, "详情"))!!)
        assertEquals("内嵌", mergeNeteaseSongs(ids, inline, extra).single().title)
    }

    @Test
    fun `字段映射完整_封面带缩放参数`() {
        val song = neteaseSong(track(1001L, "小星星", artist = "张震岳", album = "我是海雅谷慕", pic = "https://p1.music.126.net/a.jpg"))!!
        assertEquals("imp-ne-1001", song.id)
        assertEquals("小星星", song.title)
        assertEquals("张震岳", song.artistName)
        assertEquals("我是海雅谷慕", song.albumName)
        assertEquals(240_000L, song.durationMs)
        assertEquals("https://p1.music.126.net/a.jpg?param=500y500", song.artworkUrl)
    }

    @Test
    fun `缺名字的曲目被跳过_但不会抛异常`() {
        val ok = track(1001L, "有名字")
        val broken = JSONObject().apply { put("id", 1002L); put("name", "") }
        val detail = JSONObject().apply { put("songs", JSONArray().put(ok).put(broken)) }
        val songs = neteaseSongsFromDetail(detail)
        assertEquals(1, songs.size)
        assertEquals("有名字", songs.single().title)
    }

    @Test
    fun `id 分片按 200 一组`() {
        val ids = (1L..450L).toList()
        val chunks = neteaseIdChunks(ids, PlaylistImporter.DETAIL_CHUNK)
        assertEquals(3, chunks.size)
        assertEquals(200, chunks[0].size)
        assertEquals(50, chunks[2].size)
        assertEquals(ids, chunks.flatten())
        assertTrue(neteaseIdChunks(emptyList(), 200).isEmpty())
    }

    @Test
    fun `没有 trackIds 时退回内嵌 tracks`() {
        val pl = JSONObject().apply {
            put("trackCount", 2)
            put("tracks", JSONArray().put(track(1L, "A")).put(track(2L, "B")))
        }
        assertEquals(emptyList<Long>(), neteaseTrackIds(pl))
        assertEquals(2, neteaseDetailSongs(pl).size)
    }
}
