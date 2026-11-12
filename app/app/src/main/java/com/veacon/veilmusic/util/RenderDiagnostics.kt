package com.veacon.veilmusic.util

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 设备与渲染能力快照（非错误日志）。
 *
 * 起因是「特定电视上专辑封面出现马赛克」这类只在个别 GPU / 显示后端复现的问题：老版本 bug report
 * 里只有 `Device MODEL · Android 13` 一行，看不出 SoC、进程位数、显存/内存、GLES 实现，也就无法判断
 * 该走哪条渲染路径。这里一次性把排查花屏真正需要的事实收集齐：
 *
 *  - SoC / board / hardware / 进程 ABI 位数（32 位进程常常是驱动问题的放大器）
 *  - CPU 核数与最高主频、物理内存与可用内存、是否 low-RAM、应用堆上限
 *  - GPU 厂牌 / 型号 / GLES 版本（自建 1x1 pbuffer 的 EGL 上下文探测，失败就只报 GLES 版本）
 *  - 屏幕逻辑与物理分辨率、刷新率、密度、HDR 能力
 *  - 渲染兼容模式的判定结果（见 [RenderCompat]）
 *
 * 全部信息只用于日志与 bug report，不采集任何用户数据、不上传。
 */
object RenderDiagnostics {

    private const val TAG = "RenderDiag"

    /** GPU 探测结果（renderer/vendor 需要真实 EGL 上下文，探测失败时为空）。 */
    data class GpuInfo(
        val renderer: String = "",
        val vendor: String = "",
        val version: String = "",
        val glEsVersion: String = "",
        val probed: Boolean = false,
    )

    /** 渲染相关事实集合：与 Android API 解耦，便于单测。 */
    data class RenderFacts(
        val manufacturer: String = "",
        val model: String = "",
        val board: String = "",
        val hardware: String = "",
        val socModel: String = "",
        val gpuRenderer: String = "",
        val gpuVendor: String = "",
        val glEsVersion: String = "",
        val supportedAbis: List<String> = emptyList(),
        val is64BitProcess: Boolean = true,
        val isLowRamDevice: Boolean = false,
        val totalRamMb: Int = 0,
        val sdkInt: Int = Build.VERSION.SDK_INT,
    )

    private val gpuCache = AtomicReference<GpuInfo?>(null)

    /** 单行摘要：启动时打进运行日志，出问题时一眼能看出是哪台机器。 */
    fun summaryLine(context: Context): String {
        val f = facts(context)
        val gpu = gpuCache.get()?.let { info ->
            listOf(info.vendor, info.renderer).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown" }
        } ?: "unknown"
        return "device=${f.manufacturer} ${f.model} · Android ${Build.VERSION.RELEASE} (${f.sdkInt}) · " +
            "abi=${f.supportedAbis.firstOrNull() ?: "?"}${if (f.is64BitProcess) "/64bit" else "/32bit"} · " +
            "soc=${f.socModel.ifBlank { f.hardware.ifBlank { f.board } }} · " +
            "cpu=${runtimeCores()}核 · mem=${f.totalRamMb}MB${if (f.isLowRamDevice) " low-ram" else ""} · " +
            "gpu=$gpu · gles=${f.glEsVersion}"
    }

    /** bug report 用的多行明细。 */
    fun reportLines(context: Context): List<String> {
        val f = facts(context)
        val gpu = gpuCache.get()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = am?.let { ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) } }
        val dm = context.resources.displayMetrics
        val display = runCatching {
            @Suppress("DEPRECATION")
            val d = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
            val mode = d.mode
            val hdr = runCatching { d.hdrCapabilities.supportedHdrTypes.isNotEmpty() }.getOrDefault(false)
            "mode=${mode.physicalWidth}x${mode.physicalHeight}@${"%.1f".format(java.util.Locale.US, mode.refreshRate)}Hz " +
                "ui=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} hdr=$hdr"
        }.getOrElse { "ui=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} (probe failed)" }
        val reasons = RenderCompat.riskReasons(f)
        return buildList {
            add("SoC/board: soc=${f.socModel.ifBlank { "?" }} board=${f.board.ifBlank { "?" }} hw=${f.hardware.ifBlank { "?" }}")
            add("CPU: ${runtimeCores()} cores · max ${maxCpuKhz() / 1000}MHz")
            add("Process: ${if (f.is64BitProcess) "64-bit" else "32-bit"} · abis=${f.supportedAbis.joinToString(",")}")
            add("Memory: total=${f.totalRamMb}MB avail=${mem?.availMem?.div(1 shl 20) ?: -1}MB lowRam=${f.isLowRamDevice} " +
                "heapMax=${Runtime.getRuntime().maxMemory() / (1 shl 20)}MB")
            add("GPU: vendor=${gpu?.vendor.orEmpty().ifBlank { "?" }} renderer=${gpu?.renderer.orEmpty().ifBlank { "?" }} " +
                "gl=${gpu?.version.orEmpty().ifBlank { "?" }} es=${f.glEsVersion} probed=${gpu?.probed ?: false}")
            add("Display: $display")
            add("RenderCompat: mode=${RenderCompat.mode(context).name} active=${RenderCompat.isActive(context)} " +
                "reasons=${if (reasons.isEmpty()) "none" else reasons.joinToString("; ")}")
        }
    }

    /** 汇总当前设备事实。GPU 字段来自缓存；未探测过（或探测失败）时为空。 */
    fun facts(context: Context): RenderFacts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = am?.let { ActivityManager.MemoryInfo().also { m -> it.getMemoryInfo(m) } }
        val gpu = gpuCache.get()
        return RenderFacts(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            board = Build.BOARD.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else "",
            gpuRenderer = gpu?.renderer.orEmpty(),
            gpuVendor = gpu?.vendor.orEmpty(),
            glEsVersion = gpu?.glEsVersion?.ifBlank { deviceGlEsVersion(context) } ?: deviceGlEsVersion(context),
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            is64BitProcess = is64BitProcess(),
            isLowRamDevice = am?.isLowRamDevice ?: false,
            totalRamMb = ((mem?.totalMem ?: 0L) / (1 shl 20)).toInt(),
        )
    }

    /**
     * 后台探测一次 GPU 信息并缓存。探测会创建/销毁一个 1x1 的 EGL 上下文，
     * 只做一次、失败静默（个别电视的 EGL 实现不稳，绝不能让诊断本身把 App 拖崩）。
     * 探测线程上调用 [onResult]，方便调用方把结果打进运行日志。
     */
    fun probeGpuAsync(onResult: (GpuInfo) -> Unit = {}) {
        gpuCache.get()?.let { onResult(it); return }
        val t = Thread({
            val info = runCatching { probeGpu() }.getOrElse {
                Log.i(TAG, "GPU probe failed: ${it.javaClass.simpleName}")
                GpuInfo()
            }
            gpuCache.compareAndSet(null, info)
            runCatching { onResult(info) }
        }, "render-diag-gpu")
        t.isDaemon = true
        // GL 探测要在非主线程；给它一次机会，失败不影响任何功能。
        runCatching { t.start() }.onFailure { Log.i(TAG, "GPU probe thread not started: ${it.message}") }
    }

    internal fun is64BitProcess(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) Process.is64Bit() else false

    internal fun runtimeCores(): Int = Runtime.getRuntime().availableProcessors()

    internal fun maxCpuKhz(): Long = runCatching {
        java.io.File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { cpu ->
                java.io.File(cpu, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }
                    ?.readText()?.trim()?.toLongOrNull()
            }?.maxOrNull() ?: 0L
    }.getOrDefault(0L)

    private fun deviceGlEsVersion(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = am.deviceConfigurationInfo.reqGlEsVersion
        "${info shr 16}.${info and 0xffff}"
    }.getOrElse { "?" }

    // ── EGL 探测 ──────────────────────────────────────────────────────────

    private fun probeGpu(): GpuInfo {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return GpuInfo()
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return GpuInfo()
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                return GpuInfo()
            }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return GpuInfo()
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return GpuInfo()
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return GpuInfo()
            return GpuInfo(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                glEsVersion = "${version[0]}.${version[1]}",
                probed = true,
            )
        } finally {
            runCatching {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
        }
    }
}
