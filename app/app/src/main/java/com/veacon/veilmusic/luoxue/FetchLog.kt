package com.veacon.veilmusic.luoxue

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 每首歌一次「取链记录」的结构化日志。
 *
 * 写进应用日志（`[LX] FETCH {…}`）与 logcat，供后续统计首播耗时、命中率、各音源表现。
 * 一行一个 JSON 对象，字段固定，便于 grep / 导入表格：
 *  - `ts`        取链开始时间（UTC ISO-8601，毫秒）
 *  - `start_ms`  同一时刻的 epoch 毫秒（便于与系统日志对齐）
 *  - `elapsed_ms` 从开始到拿到结果（成功/未命中/异常都记）的总耗时
 *  - `song_id` / `title` / `artist` / `album`  可唯一定位歌曲的标识
 *  - `result`    ok | miss | cache | error
 *  - `source` / `quality` / `kbps` / `script` / `attempts` / `reason`  诊断字段
 *
 * 这是纯格式化工具：不发起请求、不碰缓存、不改解析结果，播放链路完全不受影响。
 */
internal object FetchLog {
    fun line(
        songId: String,
        title: String,
        artist: String,
        album: String,
        startMs: Long,
        elapsedMs: Long,
        result: String,
        source: String? = null,
        quality: String? = null,
        kbps: Int = 0,
        script: String? = null,
        attempts: Int = 0,
        reason: String? = null,
    ): String {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(startMs))
        return "FETCH " + JSONObject().apply {
            put("event", "fetch")
            put("ts", ts)
            put("start_ms", startMs)
            put("elapsed_ms", elapsedMs)
            put("song_id", songId)
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("result", result)
            put("source", source.orEmpty())
            put("quality", quality.orEmpty())
            put("kbps", kbps)
            put("script", script.orEmpty())
            put("attempts", attempts)
            put("reason", reason.orEmpty())
        }.toString()
    }
}
