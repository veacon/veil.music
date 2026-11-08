package com.veacon.veilmusic.media

import android.os.Build

/**
 * FFmpeg 扩展的真实可用性。
 *
 * 仓库里 `jniLibs/` 只有 `armeabi-v7a/libffmpegJNI.so`：这台电视虽然 SoC 是 arm64，
 * 但它的 abilist 把 32 位排前面，应用被装成 32 位，所以软解还能用 —— 属于运气。
 * 换一台只接受 64 位的设备，`System.loadLibrary` 会抛 UnsatisfiedLinkError，
 * Media3 的 FFmpeg 渲染器会静默不可用（不会崩，但也解不了 FLAC/APE 等）。
 *
 * 这里把它变成**可观测**的：启动日志和设置页都能看到，不用等用户报「某个格式放不出来」。
 */
internal object FfmpegSupport {

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("ffmpegJNI") }.isSuccess
    }

    val deviceAbi: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"

    fun describe(): String = "ffmpeg=${if (available) "ok" else "missing"} abi=$deviceAbi"
}
