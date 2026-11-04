package com.veil.music.data.lx

import android.content.Context
import com.veil.music.data.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌曲 id → [Track] 的注册表。
 *
 * UI 层（队列、历史、歌单缓存）只认字符串 id，但取流需要完整的 Track（songmid、extras 里的
 * kugou hash / QQ mediaMid 等）。Apple 版没有这层，是因为它只要一个 id 就能问服务器；
 * lx 脚本要求把整个 musicInfo 回传，所以必须把 Track 留住。
 *
 * 内存里是 LRU 上限的 map，落盘一份以便重启后从历史/队列继续播放。
 */
@Singleton
class TrackRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val map = object : LinkedHashMap<String, Track>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean =
            size > MAX_ENTRIES
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Track.serializer())

    private val file: File get() = File(context.filesDir, "tracks.json")

    @Volatile private var loaded = false

    fun put(track: Track): String {
        val id = LxIds.track(track.source, track.songmid)
        synchronized(lock) { map[id] = track }
        scheduleSave()
        return id
    }

    fun putAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        synchronized(lock) {
            tracks.forEach { map[LxIds.track(it.source, it.songmid)] = it }
        }
        scheduleSave()
    }

    fun get(id: String?): Track? {
        if (id.isNullOrBlank()) return null
        ensureLoaded()
        return synchronized(lock) { map[id] }
    }

    /** 注册表里没有时（缓存被清、旧版本升级）用 Song 的字段重建一个够用的 Track。 */
    fun getOrRebuild(id: String, fallback: () -> Track?): Track? =
        get(id) ?: fallback()?.also { put(it) }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        scope.launch {
            val restored = runCatching {
                if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
            }.getOrDefault(emptyList())
            synchronized(lock) { restored.forEach { map[LxIds.track(it.source, it.songmid)] = it } }
        }
    }

    /** 合并短时间内的多次写入，避免连续滚动歌单时每首歌都落一次盘。 */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_500)
            persist()
        }
    }

    fun persistNow() {
        saveJob?.cancel()
        scope.launch { persist() }
    }

    private fun persist() {
        val snapshot = synchronized(lock) { map.values.toList() }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, snapshot))
        }
    }

    private companion object {
        const val MAX_ENTRIES = 800
    }
}
