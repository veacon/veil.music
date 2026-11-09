package com.veacon.veilmusic

import com.veacon.veilmusic.data.cache.AppDiskCache
import com.veacon.veilmusic.data.cache.CacheLimits
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.veacon.veilmusic.data.MutPreferences
import com.veacon.veilmusic.data.repository.MusicRepository
import com.veacon.veilmusic.media.InAppWebServer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AppleMusicApp : Application(), ImageLoaderFactory {

    /**
     * Coil's default disk cache grows to ~2% of the whole partition — unbounded on a big
     * Fire TV. Artwork uses a 150 MB native LRU within the shared configurable disk budget.
     * Memory remains capped at 64 MiB — 15% of RAM was
     * ~225 MB on this box, far too much to hold in a memory-starved foreground app.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(64 * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(CacheLimits.IMAGE_BYTES)
                    .build()
            }
            // Fire TV menu smoothness: RGB_565 halves bitmap memory for opaque artwork (no alpha
            // needed) → far less GC churn while flinging shelves. respectCacheHeaders(false) keeps
            // decoded art in cache so re-focusing a shelf never re-fetches/re-decodes.
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .crossfade(false)
            .eventListener(object : coil.EventListener {
                override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                    com.veacon.veilmusic.ui.background.IdleArtworkPool.record(request.data)
                }
            })
            .build()

    @Inject lateinit var webServer: InAppWebServer
    @Inject lateinit var repo: MusicRepository
    @Inject lateinit var mutPrefs: MutPreferences
    @Inject lateinit var lxPlayback: com.veacon.veilmusic.luoxue.LxPlayback

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.veacon.veilmusic.i18n.AppLanguage.initialize(this)
        com.veacon.veilmusic.util.CrashReporter.install(this)
        // 音源初始化（10 个 QuickJS 脚本）在 Application 阶段就开始，与首帧绘制、首页请求
        // 并行预热；不要等窗口尺寸闸门，更不要等用户点下第一首歌才开始加载脚本。
        lxPlayback.logger = { msg -> webServer.addLog("LX", msg) }
        appScope.launch { runCatching { lxPlayback.ensureReady() } }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.veacon.veilmusic.i18n.AppLanguage.configurationChanged(newConfig)
    }

    /** Start main-app work only after the current window passes the size gate. */
    private var servicesStarted = false
    @Synchronized fun startMainServices() {
        if (servicesStarted) { webServer.boot(appScope); return }
        servicesStarted = true
        // 苹方字体已随包内置（res/font/pingfang.otf），不再运行时下载 Noto CJK。
        AppDiskCache.get(this).start(appScope)
        webServer.boot(appScope)   // starts only if the Dev toggle is on
        // Route volume-leveling diagnostics into the APP log (so they show under App Log, not Network,
        // and stream live on the :8081 event port).
        com.veacon.veilmusic.media.GainProcessor.logger = { tag, msg -> webServer.addLog(tag, msg) }
        // 落雪音源的日志接线与后台预热已提前到 onCreate()，这里只负责依赖窗口闸门的服务。
        // Sync locally-stored MUT to proxy server on startup so ExoPlayer stream requests work
        val mut = mutPrefs.getMUT()
        if (mut.isNotEmpty()) {
            appScope.launch {
                runCatching { repo.syncMUTToServer(mut) }
            }
        }
    }

}
