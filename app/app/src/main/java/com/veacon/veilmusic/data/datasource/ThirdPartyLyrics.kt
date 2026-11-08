package com.veacon.veilmusic.data.datasource

import android.util.Log
import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.media.awaitTextResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 第三方歌词源（网易云 / QQ / 酷狗 / 酷我 / 咪咕）。
 *
 * 为什么需要它：Apple 的 TTML 歌词关系必须带 Media-User-Token，未登录一律 404；
 * 而本 app 不再要求登录。所以歌词改由国内五大平台提供，用「标题 + 艺人 + 时长」
 * 在各平台上匹配同名曲目。
 *
 * 设计取舍：
 *  - 独立 OkHttpClient：绝不能带上 Apple 的 Authorization / Cookie 拦截器。
 *  - 两段式（搜索 → 取词）：平台搜索接口返回的候选里有时长和艺人，能有效
 *    避免「同名不同曲」拿到错词。
 *  - 只依赖公开接口（不带 cookie / 不做登录），失败就交给下一个源。
 *  - 顺序即优先级：网易云 → QQ → 酷狗 → 酷我 → 咪咕，第一个命中的即返回。
 */
@Singleton
class ThirdPartyLyrics @Inject constructor() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private companion object {
        const val TAG = "ThirdPartyLyrics"
        const val MIN_SCORE = 42.0
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    private data class Hit(
        val id: String,
        val title: String,
        val artists: String,
        val durationSec: Int,
        val score: Double,
        val extra: String = "",
    )

    // ── 对外 ────────────────────────────────────────────────────────────────

    /** 歌词 + 命中的来源（"网易云" / "QQ" / "酷狗" / "酷我" / "咪咕"）。供「歌词来源」展示。 */
    data class Result(val lines: List<LyricLine>, val source: String)

    /**
     * 按优先级级联：网易云 → QQ → 酷狗 → 酷我 → 咪咕，返回第一条可用歌词；都没有则空。
     *
     * [excludeSources] 是「歌词换源」要跳过的源标签集合（如 {"网易云"}），命中后从下一个源开始试，
     * 避免换源又落回同一个平台。为空则正常全级联。
     */
    suspend fun fetch(title: String, artist: String, durationSec: Long, excludeSources: Set<String> = emptySet()): Result {
        if (title.isBlank()) return Result(emptyList(), "")
        if ("网易云" !in excludeSources) {
            netease(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "网易云") }
        }
        if ("QQ音乐" !in excludeSources) {
            qq(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "QQ音乐") }
        }
        if ("酷狗" !in excludeSources) {
            kugou(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "酷狗") }
        }
        if ("酷我" !in excludeSources) {
            kuwo(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "酷我") }
        }
        if ("咪咕" !in excludeSources) {
            migu(title, artist, durationSec).takeIf { it.isNotEmpty() }
                ?.let { return Result(it, "咪咕") }
        }
        return Result(emptyList(), "")
    }

    // ── 网易云 ──────────────────────────────────────────────────────────────

    private suspend fun netease(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://music.163.com/api/search/get/web?type=1&limit=10&s=" + enc(q)
                val body = http.newCall(
                    Request.Builder().url(url)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://music.163.com/")
                        .build()
                ).awaitTextResponse().body
                val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return@withContext emptyList()
                val hits = (0 until songs.length()).mapNotNull { i ->
                    val o = songs.optJSONObject(i) ?: return@mapNotNull null
                    val artists = o.optJSONArray("artists")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = (o.optLong("duration", 0L) / 1000L).toInt()
                    Hit(
                        id = o.optString("id"),
                        title = o.optString("name"),
                        artists = artists,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("name"), artists, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = neteaseLyric(h.id) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "netease hit id=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "netease failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    private suspend fun neteaseLyric(id: String): String? = try {
        val url = "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1"
        val body = http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://music.163.com/")
                .build()
        ).awaitTextResponse().body
        JSONObject(body).optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── QQ 音乐 ─────────────────────────────────────────────────────────────

    private suspend fun qq(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                    "?format=json&n=10&cr=1&g_tk=5381&w=" + enc(q)
                val body = http.newCall(
                    Request.Builder().url(url)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://y.qq.com/")
                        .build()
                ).awaitTextResponse().body
                val list = JSONObject(jsonOnly(body)).optJSONObject("data")
                    ?.optJSONObject("song")?.optJSONArray("list") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val singers = o.optJSONArray("singer")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optJSONObject(it)?.optString("name").orEmpty() }
                    }.orEmpty()
                    val duration = o.optInt("interval", 0)
                    Hit(
                        id = o.optString("songmid"),
                        title = o.optString("songname"),
                        artists = singers,
                        durationSec = duration,
                        score = score(title, artist, durationSec, o.optString("songname"), singers, duration),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = qqLyric(h.id) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "qq hit mid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "qq failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    private suspend fun qqLyric(songmid: String): String? = try {
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$songmid&format=json&nobase64=1&g_tk=5381"
        val body = http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://y.qq.com/portal/player.html")
                .build()
        ).awaitTextResponse().body
        JSONObject(jsonOnly(body)).optString("lyric").takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── 酷狗 ────────────────────────────────────────────────────────────────

    private suspend fun kugou(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                // 搜索拿 hash（歌词需要 hash 参与 accesskey 计算）
                val searchUrl = "https://songsearch.kugou.com/song_search_v2?keyword=" + enc(q) +
                    "&page=1&pagesize=8&platform=WebFilter"
                val body = http.newCall(
                    Request.Builder().url(searchUrl)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://www.kugou.com/")
                        .build()
                ).awaitTextResponse().body
                val list = JSONObject(body).optJSONObject("data")?.optJSONArray("lists") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("SongName").replace(Regex("<[^>]+>"), "")
                    val singers = o.optString("SingerName").replace(Regex("<[^>]+>"), "")
                    Hit(
                        id = o.optString("FileHash"),
                        title = name,
                        artists = singers,
                        durationSec = o.optInt("Duration"),
                        score = score(title, artist, durationSec, name, singers, o.optInt("Duration")),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = kugouLyric(h.id, h.durationSec) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "kugou hit hash=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "kugou failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    /** 酷狗歌词：search 拿 id+accesskey → download 拿 base64 的 lrc。 */
    private suspend fun kugouLyric(hash: String, durationSec: Int): String? {
        return try {
        val key = kugouKey(hash, durationSec)
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=&hash=$hash&duration=${durationSec * 1000}&accesskey=$key"
        val body = http.newCall(
            Request.Builder().url(searchUrl)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://www.kugou.com/")
                .build()
        ).awaitTextResponse().body
        val candidates = JSONObject(body).optJSONArray("candidates") ?: return null
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id")
        val accesskey = first.optString("accesskey")
        if (id.isBlank() || accesskey.isBlank()) return null
        val dl = http.newCall(
            Request.Builder().url("https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8")
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://www.kugou.com/")
                .build()
        ).awaitTextResponse().body
        val content = JSONObject(dl).optString("content").takeIf { it.isNotBlank() } ?: return null
        String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 酷狗 accesskey：md5(hash + "kgcloud") 的小写 hex。 */
    private fun kugouKey(hash: String, durationSec: Int): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest("${hash}kgcloud".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── 酷我 ────────────────────────────────────────────────────────────────

    private suspend fun kuwo(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val searchUrl = "https://search.kuwo.cn/r.s?all=" + enc(q) +
                    "&ft=music&rformat=json&encoding=utf8&rn=10&vipver=MUSIC_8.7.7.0&plat=pc"
                val body = http.newCall(
                    Request.Builder().url(searchUrl)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://www.kuwo.cn/")
                        .build()
                ).awaitTextResponse().body
                val root = JSONObject(jsonOnly(body))
                val list = root.optJSONArray("abslist") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("NAME")
                    val singers = o.optString("ARTIST")
                    val dur = o.optInt("DURATION")
                    Hit(
                        id = o.optString("MUSICRID").removePrefix("MUSIC_"),
                        title = name,
                        artists = singers,
                        durationSec = dur,
                        score = score(title, artist, durationSec, name, singers, dur),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lines = kuwoLyric(h.id) ?: continue
                    Log.i(TAG, "kuwo hit rid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "kuwo failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    /** 酷我歌词：songinfoandlrc 返回 lrclist（time + lineLyric）。 */
    private suspend fun kuwoLyric(rid: String): List<LyricLine>? {
        return try {
            val url = "https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$rid&httpsStatus=1"
            val body = http.newCall(
                Request.Builder().url(url)
                    .addHeader("User-Agent", UA)
                    .addHeader("Referer", "https://m.kuwo.cn/")
                    .build()
            ).awaitTextResponse().body
            val lrclist = JSONObject(body).optJSONObject("data")?.optJSONArray("lrclist") ?: return null
            val out = mutableListOf<Pair<Long, String>>()
            for (i in 0 until lrclist.length()) {
                val o = lrclist.optJSONObject(i) ?: continue
                val t = o.optString("time").toDoubleOrNull() ?: continue
                val line = o.optString("lineLyric").takeIf { it.isNotBlank() } ?: continue
                out.add(((t * 1000).toLong()) to line)
            }
            if (out.isEmpty()) return null
            out.sortBy { it.first }
            out.mapIndexed { i, (startMs, text) ->
                val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
                LyricLine(startMs, endMs, text, emptyList(), null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ── 咪咕 ────────────────────────────────────────────────────────────────

    private suspend fun migu(title: String, artist: String, durationSec: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            try {
                val q = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val searchUrl = "https://m.music.migu.cn/migu/remoting/scr_search_tag?pgc=1&rows=10" +
                    "&keyword=" + enc(q) + "&type=2"
                val body = http.newCall(
                    Request.Builder().url(searchUrl)
                        .addHeader("User-Agent", UA)
                        .addHeader("Referer", "https://m.music.migu.cn/")
                        .build()
                ).awaitTextResponse().body
                val root = JSONObject(body)
                val list = root.optJSONArray("musics") ?: return@withContext emptyList()
                val hits = (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("songName")
                    val singers = o.optJSONArray("singerName")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.optString(it) }
                    }.orEmpty()
                    val cpid = o.optString("copyrightId")
                    Hit(
                        id = cpid,
                        title = name,
                        artists = singers,
                        durationSec = 0,
                        score = score(title, artist, durationSec, name, singers, 0),
                    )
                }.filter { it.id.isNotBlank() && it.score >= MIN_SCORE }
                    .sortedByDescending { it.score }

                for (h in hits.take(3)) {
                    val lrc = miguLyric(h.id) ?: continue
                    val lines = LyricsLrc.parse(lrc)
                    Log.i(TAG, "migu hit cpid=${h.id} score=${h.score.toInt()} lines=${lines.size} '$title'")
                    if (lines.isNotEmpty()) return@withContext lines
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "migu failed: ${e.javaClass.simpleName} ${e.message}")
                emptyList()
            }
        }

    /** 咪咕歌词：getLyric 返回 lrcText（LRC 文本）。 */
    private suspend fun miguLyric(copyrightId: String): String? = try {
        val url = "https://music.migu.cn/v3/api/music/audioPlayer/getLyric?copyrightId=" + enc(copyrightId)
        val body = http.newCall(
            Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", "https://music.migu.cn/")
                .build()
        ).awaitTextResponse().body
        JSONObject(body).optString("lyric").takeIf { it.isNotBlank() }
            ?: JSONObject(body).optString("lrcText").takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── 匹配打分 ────────────────────────────────────────────────────────────

    private fun score(
        wantTitle: String, wantArtist: String, wantDur: Long,
        gotTitle: String, gotArtists: String, gotDur: Int,
    ): Double {
        val t = similarity(norm(wantTitle), norm(gotTitle))
        val a = similarity(norm(wantArtist), norm(gotArtists))
        val d = when {
            wantDur <= 0L || gotDur <= 0 -> 0.5
            kotlin.math.abs(wantDur - gotDur) <= 2 -> 1.0
            kotlin.math.abs(wantDur - gotDur) <= 5 -> 0.7
            kotlin.math.abs(wantDur - gotDur) <= 12 -> 0.35
            else -> 0.0
        }
        // 标题是硬指标，艺人/时长只在标题接近时才加权
        return (t * 60.0) + (a * 25.0) + (d * 15.0)
    }

    /** 去掉括号内的版本说明、空格与标点，小写化。 */
    private fun norm(s: String): String = s.lowercase()
        .replace(Regex("""[（(\[【].*?[）)\]】]"""), "")
        .replace(Regex("""[\s\-_·・.,，。!！?？'"“”‘’]"""), "")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.85
        return dice(a, b)
    }

    /** 字符二元组 Dice 系数，对中英文都还算稳。 */
    private fun dice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        var inter = 0
        val pool = gb.toMutableMap()
        for ((g, n) in ga) {
            val have = pool[g] ?: 0
            if (have > 0) {
                val use = minOf(n, have)
                inter += use
                pool[g] = have - use
            }
        }
        return 2.0 * inter / (a.length - 1 + b.length - 1)
    }

    private fun grams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            m[g] = (m[g] ?: 0) + 1
        }
        return m
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** QQ 的 fcg 接口偶尔套一层 callback(...)，截出纯 JSON。 */
    private fun jsonOnly(body: String): String {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        return if (start >= 0 && end > start) body.substring(start, end + 1) else body
    }
}

/** LRC → LyricLine。与 Apple 通道共用同一套时间轴语义。 */
internal object LyricsLrc {

    private val tagRe = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** 过滤 [ti:] / [ar:] / [offset:] 这类元信息行，只保留带时间戳的正文。 */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lines()) {
            val text = raw.replace(tagRe, "").trim()
            if (text.isEmpty()) continue
            if (!tagRe.containsMatchIn(raw)) continue
            for (m in tagRe.findAll(raw)) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out.add((min * 60000 + sec * 1000 + frac) to text)
            }
        }
        out.sortBy { it.first }
        return out.mapIndexed { i, (startMs, text) ->
            val endMs = if (i + 1 < out.size) out[i + 1].first else startMs + 5000
            LyricLine(startMs, endMs, text, emptyList(), null)
        }
    }
}
