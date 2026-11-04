package com.veil.music.data.playback

import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Quality
import com.veil.music.data.model.Track
import com.veil.music.source.ResolvedTrack
import com.veil.music.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放地址解析器 —— lx 里 `handleGetOnlineMusicUrl` + URL 缓存的对应物。
 *
 * 三件事：
 *  1. 同一首歌的并发解析只打一次接口（快速连点下一首时，四个协程只会有一个真的去求脚本）；
 *  2. 解析结果按「源 + songmid + 音质」缓存在内存里，TTL 10 分钟 —— 平台 URL 通常带时效，
 *     缓存太久会拿到 403 的地址；
 *  3. 把「换源提示」「降级提示」原样带回 UI，[ResolvedTrack.warning] 已经是中文。
 */
@Singleton
class PlaybackResolver @Inject constructor(
    private val sourceManager: SourceManager,
    private val prefs: VeilPreferences,
) {
    private data class Entry(val value: ResolvedTrack, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ResolvedTrack>>()

    suspend fun resolve(track: Track, quality: Quality? = null, refresh: Boolean = false): ResolvedTrack {
        val target = quality ?: prefs.playQuality.value
        val key = "${track.source.key}:${track.songmid}:${target.key}"
        if (!refresh) cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.value }

        val mine = inFlight[key]
        if (mine != null) return mine.await()

        return coroutineScope {
            val job = async { doResolve(track, target) }
            val existing = (inFlight as ConcurrentHashMap).putIfAbsent(key, job)
            if (existing != null) {
                job.cancel()
                existing.await()
            } else {
                try {
                    val resolved = job.await()
                    cache[key] = Entry(resolved, System.currentTimeMillis())
                    trimCache()
                    resolved
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private suspend fun doResolve(track: Track, quality: Quality): ResolvedTrack {
        sourceManager.fallbackEnabled = prefs.builtinFallback.value
        return sourceManager.resolveMusicURL(track, quality, allowToggleSource = prefs.toggleSource.value)
    }

    /** 播放失败（403 / 404 / 解码失败）时丢掉这一条，下次强制重新解析。 */
    fun invalidate(track: Track, quality: Quality? = null) {
        val target = quality ?: prefs.playQuality.value
        cache.remove("${track.source.key}:${track.songmid}:${target.key}")
    }

    fun clear() = cache.clear()

    private fun trimCache() {
        if (cache.size <= MAX_ENTRIES) return
        val now = System.currentTimeMillis()
        // 用 Kotlin 的 removeAll(predicate) 而不是 Collection.removeIf：后者是 API 24 才有的
        // 默认方法，在 Android 6/7 上会 NoSuchMethodError（Fire OS 6 就是 7.1）。
        cache.entries.removeAll { now - it.value.at > TTL_MS }
        if (cache.size > MAX_ENTRIES) {
            cache.entries.sortedBy { it.value.at }.take(cache.size - MAX_ENTRIES).forEach { cache.remove(it.key) }
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 120
    }
}
