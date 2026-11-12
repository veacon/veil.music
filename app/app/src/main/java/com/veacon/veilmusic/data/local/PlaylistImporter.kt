package com.veacon.veilmusic.data.local

import android.util.Log
import com.veacon.veilmusic.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 歌单导入：把 QQ 音乐 / 网易云 / 酷狗 的公开歌单（或纯文本歌曲列表）解析成本地曲目。
 *
 * 与开源实现（GoMusic、music-api-server、Superheroff/musicapi）的做法一致：
 *  - QQ：`fcg_v8_playlist_cp.fcg`（无需登录，公开歌单直接返回 songlist）；
 *  - 网易云：`api/v6/playlist/detail`（一次最多 1000 首，含封面与时长）；
 *  - 酷狗：`mobilecdn.kugou.com/api/v3/special/{detail,song}`。
 *
 * 解析出的 Song 不带 Apple id，播放时由 [com.veacon.veilmusic.luoxue.AudioResolver]
 * 按「标题 + 歌手 + 时长」在各平台搜索，无需先匹配 Apple 曲库。
 */
@Singleton
class PlaylistImporter @Inject constructor(
    @Named("direct") http: OkHttpClient,
    private val store: LocalLibraryStore,
) {

    sealed interface Result {
        data class Ok(val playlist: LocalLibraryStore.LocalPlaylist, val imported: Int, val total: Int) : Result
        data class Err(val message: String) : Result
    }

    private val client = http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val urlRegex = Regex("""https?://[^\s"'<>，。、；：）】]+""")
    private val bareIdRegex = Regex("""[?&#](?:id|disstid|specialid)=(\d{4,})""")
    private val pathIdRegex = Regex("""/(?:playlist|taoge|single|diss)/(\d{4,})""")

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0 Safari/537.36"

    suspend fun import(raw: String, preferredName: String? = null): Result = withContext(Dispatchers.IO) {
        val input = raw.trim()
        if (input.isEmpty()) return@withContext Result.Err("请输入歌单链接或歌曲列表")

        val url = urlRegex.find(input)?.value
        if (url == null) return@withContext importText(input, preferredName)

        try {
            val resolved = resolveShortUrl(url)
            val id = extractId(input) ?: extractId(resolved) ?: scrapeIdFromPage(resolved)
            if (id == null) {
                return@withContext Result.Err("没有识别出歌单 ID，请确认是公开歌单链接")
            }
            when {
                isQQ(resolved) || isQQ(input) -> importQQ(id, preferredName)
                isNetease(resolved) || isNetease(input) -> importNetease(id, preferredName)
                isKugou(resolved) || isKugou(input) -> importKugou(id, preferredName)
                else -> Result.Err("暂不支持该平台，目前支持 QQ 音乐 / 网易云 / 酷狗")
            }
        } catch (e: Exception) {
            Log.w(TAG, "import failed: ${e.message}")
            Result.Err("导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ── 平台实现 ────────────────────────────────────────────────────────────

    private fun importQQ(id: String, preferredName: String?): Result {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg" +
            "?id=$id&format=json&newsong=1&platform=yqq&hostUin=0&needNewCode=0&utf8=1"
        val body = get(url, mapOf("Referer" to "https://y.qq.com/")) ?: return Result.Err("QQ 音乐接口无响应")
        val root = JSONObject(body)
        val cdlist = root.optJSONObject("data")?.optJSONArray("cdlist")
        val cd = if (cdlist != null && cdlist.length() > 0) cdlist.optJSONObject(0) else null
            ?: return Result.Err("歌单不存在或已设为私密")
        val list = cd.optJSONArray("songlist") ?: return Result.Err("该歌单没有公开曲目")
        val songs = ArrayList<Song>(list.length())
        for (i in 0 until list.length()) {
            val s = list.optJSONObject(i) ?: continue
            val title = s.optString("name").ifBlank { s.optString("title") }
            if (title.isBlank()) continue
            val mid = s.optString("mid").ifBlank { s.optString("songmid") }
            val singers = s.optJSONArray("singer")
            val artists = buildList {
                if (singers != null) for (j in 0 until singers.length()) {
                    singers.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val album = s.optJSONObject("album")
            val albumName = album?.optString("name").orEmpty()
            val albumMid = album?.optString("mid").orEmpty()
            songs += Song(
                id = "imp-qq-${mid.ifBlank { title.hashCode().toString() }}",
                title = title,
                artistName = artists.joinToString(" / ").ifBlank { "未知艺人" },
                albumName = albumName,
                durationMs = s.optInt("interval", 0) * 1000L,
                artworkUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" },
                artworkBgColor = null,
                previewUrl = null,
            )
        }
        val name = cd.optString("dissname").ifBlank { preferredName ?: "QQ 歌单" }
        val cover = cd.optString("logo").ifBlank { cd.optString("headurl") }.takeIf { it.isNotBlank() }
        // 旧接口一次性返回全部曲目（实测 1292 首也完整）；如果哪天变了，日志里能立刻看出来。
        val declared = cd.optInt("total_song_num", list.length())
        if (declared > list.length()) Log.w(TAG, "QQ playlist $id truncated: ${list.length()}/$declared")
        return save(name, cover, LocalLibraryStore.SOURCE_QQ, "https://y.qq.com/n/ryqq/playlist/$id", songs, maxOf(declared, songs.size))
    }

    private fun importNetease(id: String, preferredName: String?): Result {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000&s=0"
        val body = get(url, mapOf("Referer" to "https://music.163.com/")) ?: return Result.Err("网易云接口无响应")
        val root = JSONObject(body)
        val pl = root.optJSONObject("playlist") ?: return Result.Err("歌单不存在或已设为私密")
        val declared = maxOf(pl.optInt("trackCount", 0), pl.optInt("songCount", 0))

        // 未登录时 /playlist/detail 的 `tracks` 会被服务端截断（实测用户歌单只回前 10 首，
        // `n=1000` 也无效），但 `trackIds` 是**完整**的。缺的曲目用 /song/detail 分片补齐，
        // 这样「网易云导入只显示几首」就不存在了。
        val ids = neteaseTrackIds(pl)
        val inline = neteaseDetailSongs(pl)
        val songs = if (ids.isNotEmpty() && ids.size > inline.size) {
            val missing = ids.filter { trackId -> inline.none { it.id == "imp-ne-$trackId" } }
            Log.i(TAG, "netease detail returned ${inline.size}/${ids.size} tracks; fetching ${missing.size} more")
            mergeNeteaseSongs(ids, inline, fetchNeteaseDetails(missing))
        } else inline

        if (songs.isEmpty()) return Result.Err("该歌单没有公开曲目")
        val name = pl.optString("name").ifBlank { preferredName ?: "网易云歌单" }
        val cover = pl.optString("coverImgUrl").takeIf { it.isNotBlank() }
        return save(
            name, cover, LocalLibraryStore.SOURCE_NETEASE,
            "https://music.163.com/#/playlist?id=$id", songs,
            declared.coerceAtLeast(songs.size),
        )
    }

    /** 用 `trackIds` 分片拉取完整曲目（POST /api/v3/song/detail，`c=[{id:..}]`）。 */
    private fun fetchNeteaseDetails(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<Song>(ids.size)
        for (chunk in neteaseIdChunks(ids, DETAIL_CHUNK)) {
            val c = chunk.joinToString(",", "[", "]") { "{\"id\":\"$it\"}" }
            val body = postForm(
                "https://music.163.com/api/v3/song/detail",
                mapOf("c" to c),
                mapOf("Referer" to "https://music.163.com/"),
            ) ?: continue
            runCatching { out += neteaseSongsFromDetail(JSONObject(body)) }
                .onFailure { Log.w(TAG, "netease song/detail parse failed: ${it.message}") }
        }
        return out
    }

    private fun importKugou(id: String, preferredName: String?): Result {
        var name = preferredName ?: "酷狗歌单"
        var cover: String? = null
        runCatching {
            val detail = get(
                "http://mobilecdn.kugou.com/api/v3/special/detail?specialid=$id&plat=0&version=8000",
                emptyMap(),
            )
            if (detail != null) {
                val d = JSONObject(detail).optJSONObject("data")
                d?.optString("specialname")?.takeIf { it.isNotBlank() }?.let { name = it }
                d?.optString("imgurl")?.takeIf { it.isNotBlank() }?.let { cover = it }
            }
        }
        // detail 接口常年返回空（27 字节），名字改从分享页 <title> 取。
        if (name == "酷狗歌单" || cover == null) {
            runCatching {
                val html = get("https://www.kugou.com/yy/special/single/$id.html", emptyMap())
                    ?: return@runCatching
                Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                    .find(html)?.groupValues?.getOrNull(1)
                    ?.substringBefore("_")?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { name = it }
            }
        }

        val songs = ArrayList<Song>()
        var total = 0
        var page = 1
        // 每页 100、最多 1000 首（与 MAX_TRACKS 对齐）；够数就提前退出，不白拉空页。
        while (page <= 10 && songs.size < MAX_TRACKS) {
            val body = get(
                "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=$page&pagesize=100&version=8000&plat=0",
                emptyMap(),
            ) ?: break
            val data = JSONObject(body).optJSONObject("data") ?: break
            total = data.optInt("total", total)
            val info = data.optJSONArray("info") ?: break
            if (info.length() == 0) break
            for (i in 0 until info.length()) {
                val s = info.optJSONObject(i) ?: continue
                val filename = s.optString("filename")
                if (filename.isBlank()) continue
                // 酷狗 filename 是「歌手 - 歌名」
                val dash = filename.indexOf(" - ")
                val artist = if (dash > 0) filename.substring(0, dash).trim() else "未知艺人"
                val title = if (dash > 0) filename.substring(dash + 3).trim() else filename.trim()
                songs += Song(
                    id = "imp-kg-${s.optString("hash").ifBlank { s.optString("audio_id") }}",
                    title = title,
                    artistName = artist,
                    albumName = s.optString("album_name"),
                    durationMs = s.optInt("duration", 0) * 1000L,
                    artworkUrl = null,
                    artworkBgColor = null,
                    previewUrl = null,
                )
            }
            if (songs.size >= total) break
            page++
        }
        if (songs.isEmpty()) return Result.Err("歌单不存在或没有公开曲目")
        return save(name, cover, LocalLibraryStore.SOURCE_KUGOU, "https://www.kugou.com/yy/special/single/$id.html", songs, total)
    }

    /** 纯文本：每行 `歌名 - 歌手[- 专辑]`、`歌名<TAB>歌手` 或只有歌名。 */
    private fun importText(text: String, preferredName: String?): Result {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return Result.Err("没有可导入的歌曲行")
        val songs = ArrayList<Song>(lines.size)
        for (line in lines.take(MAX_TRACKS)) {
            val parts = when {
                line.contains('\t') -> line.split('\t').map { it.trim() }
                line.contains(" - ") -> line.split(" - ").map { it.trim() }
                line.contains(" – ") -> line.split(" – ").map { it.trim() }
                else -> listOf(line)
            }
            val title = parts.getOrNull(0).orEmpty()
            if (title.isBlank() || title.length > 120) continue
            // 跳过明显的表头/统计行
            if (title.startsWith("共") && title.endsWith("首")) continue
            songs += Song(
                id = "imp-txt-${title.hashCode()}-${parts.getOrNull(1)?.hashCode() ?: 0}",
                title = title,
                artistName = parts.getOrNull(1).orEmpty().ifBlank { "未知艺人" },
                albumName = parts.getOrNull(2).orEmpty(),
                durationMs = 0L,
                artworkUrl = null,
                artworkBgColor = null,
                previewUrl = null,
            )
        }
        if (songs.isEmpty()) return Result.Err("没有识别出歌曲，格式示例：歌名 - 歌手")
        val name = preferredName?.takeIf { it.isNotBlank() } ?: "文本歌单 ${songs.size} 首"
        return save(name, null, LocalLibraryStore.SOURCE_TEXT, null, songs, songs.size)
    }

    private fun save(
        name: String,
        cover: String?,
        source: String,
        sourceUrl: String?,
        songs: List<Song>,
        total: Int,
    ): Result {
        if (songs.isEmpty()) return Result.Err("歌单里没有可导入的曲目")
        val playlist = LocalLibraryStore.LocalPlaylist(
            id = "imp-$source-${sourceUrl?.hashCode() ?: name.hashCode()}",
            name = name,
            coverUrl = cover,
            source = source,
            sourceUrl = sourceUrl,
            songs = songs.take(MAX_TRACKS),
        )
        store.saveImported(playlist)
        Log.i(TAG, "imported '$name' source=$source songs=${songs.size}/$total")
        return Result.Ok(playlist, songs.size, total)
    }

    // ── 链接处理 ────────────────────────────────────────────────────────────

    private fun isQQ(url: String) = url.contains("y.qq.com") || url.contains("qq.com") && url.contains("taoge")
    private fun isNetease(url: String) = url.contains("music.163.com") || url.contains("163cn.tv") || url.contains("y.music.163.com")
    private fun isKugou(url: String) = url.contains("kugou.com")

    private fun extractId(raw: String): String? {
        bareIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        pathIdRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** 短链（163cn.tv / c6.y.qq.com/base/u?__= / url.cn）展开成真实地址。 */
    private fun resolveShortUrl(url: String): String {
        if (extractId(url) != null) return url
        val looksShort = listOf("163cn.tv", "c6.y.qq.com", "url.cn", "t.cn", "u.y.qq.com").any { url.contains(it) }
        if (!looksShort) return url
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", ua).build()
            client.newCall(req).execute().use { it.request.url.toString() }
        }.getOrDefault(url)
    }

    /** 短链落到 H5 页时，从 HTML 里捞歌单 id。 */
    private fun scrapeIdFromPage(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Referer", "https://y.qq.com/").build()
        client.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val patterns = listOf(
                Regex("""disstid["']?\s*[:=]\s*["']?(\d{5,})"""),
                Regex("""playlistId["']?\s*[:=]\s*["']?(\d{5,})"""),
                Regex("""/playlist/(\d{5,})"""),
                Regex("""taoge\.html\?id=(\d{5,})"""),
            )
            patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
        }
    }.getOrNull()

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", ua)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} $url")
                return@use null
            }
            resp.body?.string()
        }
    }.getOrElse {
        Log.w(TAG, "request failed $url: ${it.message}")
        null
    }

    /** 表单 POST：网易云的 song/detail 只接受 `c=[...]` 这种表单体。 */
    private fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String>): String? = runCatching {
        val form = okhttp3.FormBody.Builder()
        fields.forEach { (k, v) -> form.add(k, v) }
        val builder = Request.Builder().url(url).header("User-Agent", ua).post(form.build())
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} POST $url")
                return@use null
            }
            resp.body?.string()
        }
    }.getOrElse {
        Log.w(TAG, "request failed POST $url: ${it.message}")
        null
    }

    companion object {
        private const val TAG = "PlaylistImporter"
        private const val MAX_TRACKS = 1000

        /** song/detail 单次最多带的 id 数：网易云对超长 `c=` 会直接 400，200 实测稳定。 */
        internal const val DETAIL_CHUNK = 200
    }
}

// ── 网易云解析（纯函数，便于离线单测）─────────────────────────────────────

/** `playlist.detail` 的 `trackIds`：完整曲目 id（未登录时 `tracks` 会被截断，这里不会）。 */
internal fun neteaseTrackIds(playlist: JSONObject): List<Long> {
    val arr = playlist.optJSONArray("trackIds") ?: return emptyList()
    val out = ArrayList<Long>(arr.length())
    for (i in 0 until arr.length()) {
        val id = arr.optJSONObject(i)?.optLong("id", 0L) ?: 0L
        if (id > 0L) out += id
    }
    return out
}

/** 把网易云的 track/song 对象映射成导入用 [Song]（`tracks` 与 `song/detail` 字段一致）。 */
internal fun neteaseSong(track: JSONObject): Song? {
    val title = track.optString("name")
    if (title.isBlank()) return null
    val artists = buildList {
        track.optJSONArray("ar")?.let { arr ->
            for (j in 0 until arr.length()) {
                arr.optJSONObject(j)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
    val album = track.optJSONObject("al")
    val pic = album?.optString("picUrl").orEmpty()
    return Song(
        id = "imp-ne-${track.optLong("id")}",
        title = title,
        artistName = artists.joinToString(" / ").ifBlank { "未知艺人" },
        albumName = album?.optString("name").orEmpty(),
        durationMs = track.optLong("dt", 0L),
        artworkUrl = pic.takeIf { it.isNotBlank() }?.let { "$it?param=500y500" },
        artworkBgColor = null,
        previewUrl = null,
    )
}

/** `playlist.detail` 内嵌的 `tracks`（可能被服务端截断）。 */
internal fun neteaseDetailSongs(playlist: JSONObject): List<Song> {
    val tracks = playlist.optJSONArray("tracks") ?: return emptyList()
    return (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it)?.let(::neteaseSong) }
}

/** `/api/v3/song/detail` 返回的 `songs`。 */
internal fun neteaseSongsFromDetail(json: JSONObject): List<Song> {
    val songs = json.optJSONArray("songs") ?: return emptyList()
    return (0 until songs.length()).mapNotNull { songs.optJSONObject(it)?.let(::neteaseSong) }
}

/** 按 [ids] 的原始顺序合并两个来源：内嵌的优先，缺的用详情接口补齐，多的丢弃。 */
internal fun mergeNeteaseSongs(ids: List<Long>, primary: List<Song>, extra: List<Song>): List<Song> {
    if (ids.isEmpty()) return primary
    val byId = HashMap<String, Song>(primary.size + extra.size)
    for (s in primary) byId[s.id] = s
    for (s in extra) byId.putIfAbsent(s.id, s)
    return ids.mapNotNull { byId["imp-ne-$it"] }
}

/** 把 id 列表切成每片 [size] 个。 */
internal fun neteaseIdChunks(ids: List<Long>, size: Int): List<List<Long>> {
    if (ids.isEmpty()) return emptyList()
    require(size > 0) { "chunk size must be positive" }
    return ids.chunked(size)
}
