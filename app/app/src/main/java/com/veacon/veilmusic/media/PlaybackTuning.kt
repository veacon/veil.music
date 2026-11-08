package com.veacon.veilmusic.media

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 播放内核的统一调参点。
 *
 * 这些数值原来散在三处 `buildXxx()` 里，而且是按「视频流媒体」定的：
 * 180s / 32MB 的目标缓冲，对一首 3 分钟的歌等于整首预载 —— 在 2GB 内存、
 * 堆很小的盒子上直接把水位推到 OOM（本机实测 PSS 94MB，其中 Native 30MB）。
 * 音频真正需要的只是「扛住一次网络抖动」，多出来的缓冲只占内存。
 *
 * 参照：mpv 的 `--audio-buffer` 默认 200ms；VLC 的 AudioTrack 输出上限 750ms。
 */
internal object PlaybackTuning {

    // ── ExoPlayer 侧（LoadControl）：管「从网络预读多少」──
    /** 低于这个时长就继续读。原来是 50s —— 对本地代理/直链都过于激进。 */
    const val MIN_BUFFER_MS = 20_000
    /** 读满即停。原来是 180s（整首歌都塞进内存）。 */
    const val MAX_BUFFER_MS = 60_000
    const val BUFFER_FOR_PLAYBACK_MS = 1_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    /** 往回留一点，seek 回跳不用重新下载。 */
    const val BACK_BUFFER_MS = 15_000
    /** 字节硬上限：原来 32MB，对音频只需 8MB（≈ 60s @ 1Mbps）。 */
    const val TARGET_BUFFER_BYTES = 8 * 1024 * 1024
    /** 小块分配，降低峰值（默认 64KB 分段，显式写出来免得将来被人改大）。 */
    private const val ALLOC_CHUNK_BYTES = 64 * 1024

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS, MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setBackBuffer(BACK_BUFFER_MS, false)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setAllocator(DefaultAllocator(true, ALLOC_CHUNK_BYTES))
        // 字节上限优先：宁可少缓几秒，也不要为了「攒够时长」把内存顶爆。
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    // ── AudioTrack 侧：管「交给声卡多少」──
    /**
     * 目标 PCM 缓冲。媒体3 的默认窗口是 250–750ms，电视盒子的音频 HAL 抖动比手机大，
     * 250ms 起步经常 underrun（表现为「卡一下」「爆音」）。这里把下限抬到 400ms、
     * 上限放到 1s，目标 600ms。
     */
    const val PCM_BUFFER_US = 600_000
    private const val MIN_PCM_BUFFER_US = 400_000
    private const val MAX_PCM_BUFFER_US = 1_000_000
    private const val PCM_BUFFER_FACTOR = 4

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun audioTrackBufferSizeProvider(): DefaultAudioSink.AudioTrackBufferSizeProvider =
        DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(MIN_PCM_BUFFER_US)
            .setMaxPcmBufferDurationUs(MAX_PCM_BUFFER_US)
            .setPcmBufferMultiplicationFactor(PCM_BUFFER_FACTOR)
            .setPassthroughBufferDurationUs(PCM_BUFFER_US)
            .build()

    /** 一行摘要，写进启动日志，方便事后核对「这次到底用的什么参数」。 */
    fun summary(): String =
        "buf=${MIN_BUFFER_MS / 1000}-${MAX_BUFFER_MS / 1000}s/${TARGET_BUFFER_BYTES / 1024 / 1024}MB " +
            "back=${BACK_BUFFER_MS / 1000}s at=${PCM_BUFFER_US / 1000}ms"
}
