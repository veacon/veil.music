package com.veil.music.util

import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * 「关于」页要展示的本机信息。
 *
 * 大多是固定字符串，统一在这里算好，UI 只负责排版 —— 设备信息读取牵扯
 * ActivityManager / MediaCodecList / AudioCapabilities 三套系统服务，写在 Composable 里
 * 每次重组都要重新走一遍，还容易在主线程上抖。
 */
data class DeviceReport(
    val model: String,
    val android: String,
    val abi: String,
    val screen: String,
    val ram: String,
    /** 音频输出的采样率 / 每缓冲帧数（AudioTrack 原生参数）。 */
    val output: String,
    /** 能直通给功放的编码，例如「AC3 · E-AC3 · DTS」。 */
    val passthrough: String,
    /** 平台音频解码器概况，例如「硬件 3 / 软件 5」。 */
    val decoders: String,
)

object DeviceInfo {

    fun collect(context: Context): DeviceReport {
        val dm = context.resources.displayMetrics
        // minSdk 26 起 SUPPORTED_ABIS 恒非空；Build.CPU_ABI 是已废弃的单值 API，不再兜底。
        val abis = Build.SUPPORTED_ABIS.joinToString(" / ")

        return DeviceReport(
            model = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "未知设备" },
            android = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            abi = abis.ifBlank { "—" },
            screen = "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi · " +
                "${dm.widthPixels / dm.density}×${dm.heightPixels / dm.density} dp",
            ram = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                "%.1f GB（可用 %.1f GB）".format(
                    info.totalMem / 1024.0 / 1024 / 1024,
                    info.availMem / 1024.0 / 1024 / 1024,
                )
            }.getOrDefault("—"),
            output = runCatching {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                "${rate ?: "?"} Hz · ${frames ?: "?"} 帧/缓冲"
            }.getOrDefault("—"),
            passthrough = passthroughSummary(context),
            decoders = decoderSummary(),
        )
    }

    /** 直通能力：这几档是音源脚本里真正会碰到的（杜比 / DTS 的多声道音轨）。 */
    private fun passthroughSummary(context: Context): String = runCatching {
        val caps = AudioCapabilities.getCapabilities(context)
        val supported = buildList {
            if (caps.supportsEncoding(C.ENCODING_AC3)) add("AC3")
            if (caps.supportsEncoding(C.ENCODING_E_AC3)) add("E-AC3")
            if (caps.supportsEncoding(C.ENCODING_DTS)) add("DTS")
            if (caps.supportsEncoding(C.ENCODING_DTS_HD)) add("DTS-HD")
            if (caps.supportsEncoding(C.ENCODING_AC4)) add("AC4")
        }
        if (supported.isEmpty()) "仅 PCM（无多声道直通）" else supported.joinToString(" · ")
    }.getOrDefault("—")

    /** 平台音频解码器：硬件几个、软件几个。数字对得上，就知道「软解」是不是真的切过去了。 */
    private fun decoderSummary(): String = runCatching {
        val audio = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.startsWith("audio/") } }
        val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audio.count { it.isHardwareAccelerated }
        } else {
            // isHardwareAccelerated() 是 API 29 才有的，26–28 仍按命名约定估算
            //（平台解码器都是 omx.google / c2.android）。
            audio.count { info ->
                info.name.startsWith("OMX.google", true) || info.name.startsWith("c2.android", true)
            }.let { software -> audio.size - software }
        }
        "硬件 $hardware / 软件 ${audio.size - hardware}"
    }.getOrDefault("—")
}
