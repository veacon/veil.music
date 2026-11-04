package com.veil.music.source.builtin

import android.util.Base64
import com.veil.music.data.model.SourceID
import com.veil.music.data.model.Track
import com.veil.music.lyrics.LyricLine
import com.veil.music.lyrics.LyricParser
import com.veil.music.source.catalog.CatalogHttp
import com.veil.music.source.catalog.urlEncode
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Direct lyric fetchers per platform, ported from iOS `BuiltInLyricResolver`.
 * Uses the simplest endpoints (no krc decryption).
 *
 * 稳定性改动：
 * - 结果按「平台 + 歌曲 id」缓存，切歌来回切不再重复打接口；
 * - 每家接口失败（抛异常）时自动重试一次，大户是超时 / 连接被重置；
 * - 网易云 / QQ 各准备了备选参数组合，主接口返回空就换一组再试。
 */
class BuiltInLyricResolver(private val http: CatalogHttp) {

    private val cache = ConcurrentHashMap<String, List<LyricLine>>()

    private val ua = mapOf("User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")

    fun clear() = cache.clear()

    suspend fun fetch(track: Track): List<LyricLine>? = when (track.source) {
        SourceID.KW -> cached("kw:${track.songmid}") { kuwo(track.songmid) }
        SourceID.TX -> cached("tx:${track.songmid}") { qq(track.songmid) }
        SourceID.WY -> cached("wy:${track.songmid}") { netease(track.songmid) }
        SourceID.KG -> {
            val hash = track.extras["hash"] ?: track.songmid
            cached("kg:$hash") { kugou(track.name, hash, (track.duration ?: 0) * 1000) }
        }
        else -> null
    }

    private suspend fun cached(key: String, block: suspend () -> List<LyricLine>?): List<LyricLine>? {
        cache[key]?.let { return it }
        val lines = block()
        if (!lines.isNullOrEmpty()) cache[key] = lines
        return lines
    }

    // MARK: - 跨平台按名字兜底

    /**
     * 兜底：不看曲目来自哪个平台，直接拿「歌名 + 歌手」去各大平台搜同名曲目再取歌词。
     *
     * 很多歌没歌词并不是真的没有，而是「同平台 songmid 对不上」或「换源匹配失败」——
     * 比如酷我的 lyric 接口对部分 id 直接返回 301、QQ 源的 songmid 在某些曲目上是试听 id。
     * 这里按命中率排序依次尝试：酷狗（只要有歌名+时长就能搜到）→ 网易云 → QQ。
     */
    suspend fun searchByName(track: Track): List<LyricLine>? {
        val durationMs = (track.duration ?: 0) * 1000
        // 1) 酷狗：歌词库覆盖最广，搜索接口不要求 hash
        runCatching { kugou(track.name, "", durationMs) }.getOrNull()?.let { return it }
        // 2) 网易云：搜索同名曲目 → 取 id → 取歌词
        runCatching { neteaseBySearch(track) }.getOrNull()?.let { return it }
        // 3) QQ：搜索同名曲目 → 取 songmid → 取歌词
        runCatching { qqBySearch(track) }.getOrNull()?.let { return it }
        return null
    }

    private suspend fun neteaseBySearch(track: Track): List<LyricLine>? {
        val body = "s=${urlEncode(track.searchKeyword())}&type=1&limit=5"
        val json = runCatching {
            JSONObject(
                http.postForm(
                    "https://music.163.com/api/search/get",
                    body,
                    mapOf("Referer" to "https://music.163.com/"),
                ),
            )
        }.getOrNull() ?: return null
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null
        for (i in 0 until minOf(4, songs.length())) {
            val s = songs.optJSONObject(i) ?: continue
            if (!nameMatches(s.optString("name"), track.name)) continue
            val id = s.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: continue
            netease(id)?.let { return it }
        }
        return null
    }

    private suspend fun qqBySearch(track: Track): List<LyricLine>? {
        val w = java.net.URLEncoder.encode(track.searchKeyword(), "UTF-8")
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=1&n=5&w=$w&format=json"
        val json = json(url, ua + mapOf("Referer" to "https://y.qq.com/")) ?: return null
        val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        for (i in 0 until minOf(4, list.length())) {
            val s = list.optJSONObject(i) ?: continue
            if (!nameMatches(s.optString("songname"), track.name)) continue
            val mid = s.optString("songmid").takeIf { it.isNotBlank() } ?: continue
            qq(mid)?.let { return it }
        }
        return null
    }

    private fun Track.searchKeyword(): String = "$name $singer".replace(Regex("\\s+"), " ").trim()

    /** 歌名比对：去掉标点空格后互相包含即可（「晴天 (Live)」≈「晴天」）。 */
    private fun nameMatches(candidate: String, target: String): Boolean {
        val a = normalize(candidate)
        val b = normalize(target)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.contains(b) || b.contains(a)
    }

    private fun normalize(s: String): String =
        s.replace(Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\[\\]!！?？·]"), "").lowercase()

    // MARK: - 酷我

    private suspend fun kuwo(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf("Referer" to "http://m.kuwo.cn/")
        val json = json(
            "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$songmid",
            headers,
        ) ?: return null
        val lrclist = json.optJSONObject("data")?.optJSONArray("lrclist") ?: return null
        val rebuilt = StringBuilder()
        for (i in 0 until lrclist.length()) {
            val item = lrclist.optJSONObject(i) ?: continue
            val secs = item.optString("time").toDoubleOrNull() ?: continue
            val text = item.optString("lineLyric")
            val m = secs.toInt() / 60
            val s = secs.toInt() % 60
            val ms = ((secs - secs.toInt()) * 100).toInt()
            rebuilt.append(String.format("[%02d:%02d.%02d]%s\n", m, s, ms, text))
        }
        return LyricParser.parse(rebuilt.toString()).ifEmpty { null }
    }

    // MARK: - QQ音乐

    private suspend fun qq(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf("Referer" to "https://y.qq.com/portal/player.html")
        // 两组参数，第一组拿不到就换第二组（不同版本接口返回的内容不一样）。
        val urls = listOf(
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq",
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0",
        )
        for (url in urls) {
            val json = json(url, headers) ?: continue
            if (json.optInt("code") != 0) continue
            val lrc = decodeB64(json.optString("lyric")) ?: continue
            val trans = json.optString("trans").ifEmpty { null }?.let { decodeB64(it) }
            // QQ 的新版接口在有「逐字歌词」时返回的是 QRC 格式（[起始,时长] + 词(起,时)），
            // 这里交给自动识别：有 QRC 就出音节，只有 LRC 就按标点权重估算。
            return LyricParser.parseAuto(lrc, trans).ifEmpty { null }
        }
        return null
    }

    // MARK: - 网易云

    private suspend fun netease(songmid: String): List<LyricLine>? {
        val headers = ua + mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "os=pc; appver=8.9.70",
        )
        // lv/kv/tv 取 -1 时返回完整原文（含翻译），某些 id 用 1 反而返回空，两组都试。
        // yv/yv2 是「逐字歌词」开关：网易的 YRC 是明文，不需要解密，能拿到就用它。
        val urls = listOf(
            "https://music.163.com/api/song/lyric?os=pc&id=$songmid&lv=-1&kv=-1&tv=-1&yv=-1&yv2=-1",
            "https://music.163.com/api/song/lyric?os=pc&id=$songmid&lv=1&kv=1&tv=-1&yv=-1&yv2=-1",
        )
        for (url in urls) {
            val json = json(url, headers) ?: continue
            // 优先逐字（YRC）：行头 [起始,时长]，正文 (字起始,字时长,0)字
            val yrc = json.optJSONObject("yrc")?.optString("lyric")?.ifEmpty { null }
            val ytlrc = json.optJSONObject("ytlrc")?.optString("lyric")?.ifEmpty { null }
            if (yrc != null) {
                val parsed = LyricParser.parseAuto(yrc, ytlrc)
                if (parsed.isNotEmpty()) return parsed
            }
            val lyric = json.optJSONObject("lrc")?.optString("lyric") ?: ""
            if (lyric.isEmpty()) continue
            val tlyric = json.optJSONObject("tlyric")?.optString("lyric")?.ifEmpty { null }
            return LyricParser.parseAuto(lyric, tlyric).ifEmpty { null }
        }
        return null
    }

    // MARK: - 酷狗

    private suspend fun kugou(name: String, hash: String, durationMs: Int): List<LyricLine>? {
        val headers = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager",
        )
        val nameEnc = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        // hash 为空时按「关键词 + 时长」搜（跨平台兜底就是这么用的）。
        val hashParam = if (hash.isBlank()) "" else "&hash=$hash"
        val searchUrl =
            "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$nameEnc$hashParam&timelength=$durationMs&lrctxt=1"
        val first = runCatching {
            json(searchUrl, headers)?.optJSONArray("candidates")?.optJSONObject(0)
        }.getOrNull() ?: return null
        val id = first.opt("id")?.toString() ?: return null
        val accessKey = first.optString("accesskey").ifEmpty { return null }
        val dlUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
        val dJson = json(dlUrl, headers) ?: return null
        if (dJson.optInt("status") != 200 || dJson.optString("fmt") != "lrc") return null
        val lrc = decodeB64(dJson.optString("content")) ?: return null
        return LyricParser.parse(lrc).ifEmpty { null }
    }

    // MARK: - helpers

    /** GET + JSON 解析；失败（异常 / 非 JSON）自动重试一次。 */
    private suspend fun json(url: String, headers: Map<String, String>): JSONObject? =
        twice { runCatching { JSONObject(http.getText(url, headers)) }.getOrNull() }

    private suspend fun <T> twice(block: suspend () -> T?): T? {
        block()?.let { return it }
        return runCatching { block() }.getOrNull()
    }

    private fun decodeB64(b64: String): String? =
        runCatching { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()?.ifEmpty { null }
}
