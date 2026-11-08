package com.veacon.veilmusic.media

import android.content.Context
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

@UnstableApi
class BeatAwareRenderersFactory(
    context: Context,
    val beatProcessor: BeatProcessor,
    private val gapConceal: GapConcealProcessor,
    private val gain: GainProcessor = GainProcessor(),
    /** 0 = 不重采样；>0 = 把输出统一到这个采样率（见 [OutputTuning]）。 */
    private val targetSampleRate: Int = 0,
    /** 打开后整条自定义处理器链让路，让 DSP 直接吃压缩流。 */
    private val offload: Boolean = false,
) : DefaultRenderersFactory(context) {

    /** offload 只认「没有处理器」的链路，所以这条路连 Sonic 都不挂（倍速本就锁在 1.0x）。 */
    private object NoProcessingChain : DefaultAudioSink.AudioProcessorChain {
        override fun getAudioProcessors(): Array<AudioProcessor> = emptyArray()
        override fun applyPlaybackParameters(playbackParameters: PlaybackParameters) = playbackParameters
        override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = false
        override fun getMediaDuration(mediaDurationUs: Long) = mediaDurationUs
        override fun getSkippedOutputFrameCount() = 0L
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            // 少数 TV 固件的 float 输出路径有问题（init 失败或出声异常）；出错后由
            // PlayerViewModel 把 [floatOutputAllowed] 翻成 false 再重建，退回 16bit 输出。
            .setEnableFloatOutput(enableFloatOutput && floatOutputAllowed)
            // Always route speed/pitch through Sonic instead of the platform AudioTrack. Some TV
            // firmwares keep whatever playback params an AudioTrack was handed earlier and apply them
            // again on the next reuse — which shows up as audio that "sometimes plays too fast" until
            // the sink is rebuilt. With this off, 1.0x is a bit-exact Sonic passthrough and nothing
            // outside the app can shift the rate.
            .setEnableAudioTrackPlaybackParams(false)
            // 显式给 AudioTrack 一个更大的缓冲：默认 250ms 起步，电视盒子的音频 HAL 抖动大，
            // 经常在解码边界 underrun（听感就是「卡一下」或轻微爆音）。
            .setAudioTrackBufferSizeProvider(PlaybackTuning.audioTrackBufferSizeProvider())

        if (offload) {
            builder.setAudioProcessorChain(NoProcessingChain)
            return builder.build()
        }

        // 统一输出采样率：把 Sonic 的固定输出率钉在设备原生采样率上，44.1k/48k 混排不再重建 sink。
        val sonic = SonicAudioProcessor()
        if (targetSampleRate > 0) sonic.setOutputSampleRateHz(targetSampleRate)
        builder.setAudioProcessorChain(
            // Gap repair FIRST so the decoder's dropped-frame silences are concealed before anything
            // else sees them. Gain leveling BEFORE the beat detector so the detector analyses the same
            // normalized signal the listener hears — otherwise a boosted quiet track still produces weak
            // beats. Gain only scales amplitude, never timing, so the beat stays in sync.
            DefaultAudioSink.DefaultAudioProcessorChain(
                arrayOf(gapConceal, gain, beatProcessor),
                SilenceSkippingAudioProcessor(),
                sonic,
            )
        )
        return builder.build()
    }

    companion object {
        /** 由 PlayerViewModel 在音频输出初始化失败后置 false，之后所有新播放器都退回 16bit。 */
        @Volatile
        var floatOutputAllowed: Boolean = true
    }
}
