package com.veil.music.media

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 带节拍检测的渲染工厂。
 *
 * 原来这里还串了一个 `GapConcealProcessor`，那是 Apple Music 版用来遮盖 on-device CENC
 * 解密丢帧留下的静音段的。换成 lx 音源后播放的是平台直链，没有那条解密链路，所以这段补偿
 * 被移除；节拍与音量均衡保留。
 *
 * 硬解 / 软解不在这里配：`DefaultRenderersFactory.setMediaCodecSelector(...)` 才是入口，
 * 由构建方（PlayerViewModel）决定传 `DecoderSelectors.hardwareFirst` 还是 `softwareFirst`。
 */
class BeatAwareRenderersFactory(
    context: Context,
    val beatProcessor: BeatProcessor,
    private val gain: GainProcessor = GainProcessor(),
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessorChain(
            // 增益在节拍之前：检测器分析的是听众实际听到的信号强度。
            // 增益只调幅不调时，节拍仍然对得上。
            DefaultAudioSink.DefaultAudioProcessorChain(gain, beatProcessor)
        )
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
