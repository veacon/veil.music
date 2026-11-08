package com.veacon.veilmusic.data.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放链路的磁盘缓存。
 *
 * 之前整条播放链路没有 CacheDataSource：同一首歌重播、暂停后重新 seek 回开头，
 * 都要再向 CDN 要一次字节（落雪直链还有 21 分钟 TTL 的额外风险）。
 *
 * 这里用 Media3 的 SimpleCache + LRU 淘汰，放在 cacheDir 下（系统空间紧张时可以整目录清掉，
 * 不会碰 filesDir 里的歌单/收藏）。缓存键用「歌曲 id + 容器后缀」：
 * 同一首歌重新解析出的签名 URL 每次都不同，只有 id 是稳定的；后缀用来区分同一首歌的
 * 不同音质文件（flac / mp3 / m4a），避免换了音质却命中上一种格式的缓存。
 */
@Singleton
class PlaybackCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile private var created: Cache? = null

    private val cache: Cache
        get() = created ?: synchronized(this) {
            created ?: run {
                val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
                SimpleCache(
                    dir,
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(context),
                ).also { created = it }
            }
        }

    /** 把上游数据源包一层缓存读/写。命中即秒开，未命中原样透传。 */
    @UnstableApi
    fun wrap(upstream: DataSource.Factory): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        // 缓存文件损坏时不要让整首歌播不出来。
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** 已占用字节数（诊断用；会触发缓存初始化，别在启动路径上无条件调用）。 */
    @get:UnstableApi
    val cachedBytes: Long get() = cache.cacheSpace

    companion object {
        private const val DIR_NAME = "playback_cache"
        private const val MAX_BYTES = 256L * 1024 * 1024

        /**
         * 缓存键。URL 里的签名参数每次都变，只用 songId + 容器后缀。
         * 拿不到可靠后缀时统一用 `stream`，等价于「这首歌只有一种可缓存形态」。
         */
        internal fun keyFor(songId: String, uri: String): String {
            val path = uri.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "").lowercase()
            val tag = if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext else "stream"
            return "$songId@$tag"
        }

        /** 只有远端 http(s) 值得缓存；本地代理（127.0.0.1）本来就是本机数据，缓存只会白占空间。 */
        internal fun shouldCache(uri: String): Boolean {
            if (!uri.startsWith("http://") && !uri.startsWith("https://")) return false
            val host = uri.substringAfter("://").substringBefore('/')
            val name = host.substringBefore(':')
            return name != "127.0.0.1" && name != "localhost" && name != "10.0.2.2"
        }
    }
}
