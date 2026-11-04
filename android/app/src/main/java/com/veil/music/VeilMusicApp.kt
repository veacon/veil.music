package com.veil.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.veil.music.data.store.ScriptStore
import com.veil.music.media.GainProcessor
import com.veil.music.media.InAppWebServer
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class VeilMusicApp : Application(), ImageLoaderFactory {

    @Inject lateinit var webServer: InAppWebServer
    @Inject lateinit var scripts: ScriptStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 封面加载器：内存上限 64 MB、磁盘 150 MB。
     * RGB_565 + 关闭淡入是为老电视准备的（封面多为不透明方图，半精度省一半内存）。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // Coil 2.x：内存缓存上限是 Int（字节），磁盘缓存是 Long。别把两者写成同一个类型。
            .memoryCache { MemoryCache.Builder(this).maxSizeBytes(64 * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()

    override fun onCreate() {
        super.onCreate()
        com.veil.music.util.CrashReporter.install(this)
        GainProcessor.logger = { tag, msg -> webServer.addLog(tag, msg) }
        // 管理页（8080）：默认开启，用于在手机导入脚本。关掉后不再监听。
        webServer.boot(appScope)
        // 载入已装脚本 + 补齐随包内置脚本；这一步失败不阻塞启动（重装脚本即可恢复）。
        appScope.launch {
            runCatching { scripts.loadAll() }
                .onFailure { webServer.addLog("ERROR", "脚本载入失败：${it.message}") }
        }
    }
}
