package com.veacon.veilmusic.luoxue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 平台搜索层：Apple 侧只给歌名/歌手，这里补上平台曲目 ID。
 *
 * 为什么必须自建：落雪自定义源协议只有 musicUrl（ID -> 直链），不含搜索。
 * P0 实测：只给歌名时 15 个脚本仅 1 个可用且只出 128k；补上 ID 后 7 个脚本可出 FLAC。
 */
class PlatformSearch(private val http: OkHttpClient) {

    /**
     * 三个平台并发搜索：彼此没有依赖，串行等于把首曲解析平白拖长 2 个 RTT
     * （任一平台超时还会把后面两个一起拖住）。失败的平台独立降级为空列表。
     */
    suspend fun searchAll(title: String, artist: String): List<Candidate> {
        val q = listOf(title.trim(), primaryArtist(artist))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (q.isEmpty()) return emptyList()
        return coroutineScope {
            val wy = async(Dispatchers.IO) { runCatching { netease(q) }.getOrDefault(emptyList()) }
            val tx = async(Dispatchers.IO) { runCatching { qq(q) }.getOrDefault(emptyList()) }
            val kg = async(Dispatchers.IO) { runCatching { kugou(q) }.getOrDefault(emptyList()) }
            val kw = async(Dispatchers.IO) { runCatching { kuwo(q) }.getOrDefault(emptyList()) }
            // 酷我排最前：新专辑（如周杰伦《太阳之子》）它首条即正版且带准确时长，
            // 与 QQ 同分时也必须让它先被 resolver 尝试，避免落到低码率的 QQ 转码链。
            kw.await() + wy.await() + tx.await() + kg.await()
        }
    }

    /** 跨语言曲名兜底：只按艺人搜，匹配层再用时长认歌。同样并发。 */
    suspend fun searchByArtist(artist: String, limit: Int = 12): List<Candidate> {
        val q = primaryArtist(artist)
        if (q.isEmpty()) return emptyList()
        return coroutineScope {
            val wy = async(Dispatchers.IO) { runCatching { netease(q, limit) }.getOrDefault(emptyList()) }
            val tx = async(Dispatchers.IO) { runCatching { qq(q, limit) }.getOrDefault(emptyList()) }
            val kg = async(Dispatchers.IO) { runCatching { kugou(q, limit) }.getOrDefault(emptyList()) }
            val kw = async(Dispatchers.IO) { runCatching { kuwo(q, limit) }.getOrDefault(emptyList()) }
            kw.await() + wy.await() + tx.await() + kg.await()
        }
    }

    private fun primaryArtist(artist: String) =
        artist.split("&", ",", "、", "/").first().trim()

    private fun get(url: String, referer: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────
    fun netease(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://music.163.com/api/search/get/web".toHttpUrl().newBuilder()
            .addQueryParameter("s", q)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", "0")
            .build().toString()
        val root = JSONObject(get(url, "https://music.163.com/"))
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return (0 until songs.length()).mapNotNull { i ->
            val s = songs.optJSONObject(i) ?: return@mapNotNull null
            val artists = s.optJSONArray("artists")
            val artistName = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "wy",
                id = s.optString("id"),
                title = s.optString("name"),
                artist = artistName,
                album = s.optJSONObject("album")?.optString("name").orEmpty(),
                durationSec = (s.optLong("duration") / 1000).toInt(),
                extra = mapOf("songmid" to s.optString("id"), "songId" to s.optString("id")),
            )
        }
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────
    fun qq(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp".toHttpUrl().newBuilder()
            .addQueryParameter("w", q)
            .addQueryParameter("format", "json")
            .addQueryParameter("n", limit.toString())
            .addQueryParameter("p", "1")
            .build().toString()
        val root = JSONObject(get(url, "https://y.qq.com/"))
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val singers = s.optJSONArray("singer")
            val artistName = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .joinToString(" / ")
            Candidate(
                source = "tx",
                id = s.optString("songmid"),
                title = s.optString("songname"),
                artist = artistName,
                album = s.optString("albumname"),
                durationSec = s.optInt("interval"),
                extra = mapOf(
                    "songmid" to s.optString("songmid"),
                    "strMediaMid" to s.optString("media_mid"),
                    "mid" to s.optString("songmid"),
                ),
            )
        }
    }

    // ── 酷狗 ────────────────────────────────────────────────────────────────
    fun kugou(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://songsearch.kugou.com/song_search_v2".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", q)
            .addQueryParameter("page", "1")
            .addQueryParameter("pagesize", limit.toString())
            .build().toString()
        val root = JSONObject(get(url, "https://www.kugou.com/"))
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            Candidate(
                source = "kg",
                id = s.optString("FileHash"),
                title = s.optString("SongName").replace(Regex("<[^>]+>"), ""),
                artist = s.optString("SingerName").replace(Regex("<[^>]+>"), ""),
                album = s.optString("AlbumName"),
                durationSec = s.optInt("Duration"),
                extra = mapOf(
                    "hash" to s.optString("FileHash"),
                    "album_id" to s.optString("AlbumID"),
                    "album_audio_id" to s.optString("AlbumAudioID"),
                ),
            )
        }
    }

    // ── 酷我 ────────────────────────────────────────────────────────────────
    /**
     * 酷我搜索：新专辑（如周杰伦《太阳之子》）命中率与音质都最好，必须纳入。
     * 接口返回的是单引号、&nbsp; 的非标准 JSON，先归一化再解析。
     * id 用 MUSIC_xxx（脚本的 kw 分支认 rid/musicId），数字 id 一并放 extra。
     */
    fun kuwo(q: String, limit: Int = 8): List<Candidate> {
        val url = "https://search.kuwo.cn/r.s".toHttpUrl().newBuilder()
            .addQueryParameter("all", q)
            .addQueryParameter("ft", "music")
            .addQueryParameter("rformat", "json")
            .addQueryParameter("encoding", "utf8")
            .addQueryParameter("rn", limit.toString())
            .addQueryParameter("vipver", "MUSIC_8.7.7.0")
            .addQueryParameter("plat", "pc")
            .build().toString()
        val normalized = get(url, "https://www.kuwo.cn/")
            .replace("&nbsp;", " ")
            .replace('\'', '"')
        val root = JSONObject(normalized)
        val list = root.optJSONArray("abslist") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val rid = s.optString("MUSICRID")
            if (rid.isBlank()) return@mapNotNull null
            val numericId = s.optString("MUSIC_ID").ifBlank { rid.removePrefix("MUSIC_") }
            Candidate(
                source = "kw",
                id = rid,
                title = s.optString("NAME").trim(),
                artist = s.optString("ARTIST").trim(),
                album = s.optString("ALBUM").trim(),
                durationSec = s.optInt("DURATION"),
                extra = mapOf(
                    "rid" to rid,
                    "musicId" to numericId,
                    "songid" to numericId,
                    "songId" to numericId,
                    "albumId" to s.optString("ALBUMID"),
                ),
            )
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
