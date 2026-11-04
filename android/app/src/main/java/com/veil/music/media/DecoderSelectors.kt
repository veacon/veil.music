package com.veil.music.media

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 硬解 / 软解选择。
 *
 * Android 上同一个 MIME 往往同时存在硬件解码器（`c2.qti.*` / `OMX.qcom.*`）和平台自带的
 * 软件解码器（`c2.android.*` / `OMX.google.*`）。ExoPlayer 默认按平台打分挑一个，这里我们不
 * 改变候选集合，只改变**顺序**：`MediaCodecSelector.DEFAULT` 已经是按优先级排好的列表，
 * 把软解项提到前面，`MediaCodecAudioRenderer.initCodec` 就会选中它。
 *
 * 之所以不过滤候选，是因为某些设备上某个 MIME 只有一种实现（例如部分电视的 FLAC 只有软解），
 * 直接过滤会让本来能放的歌放不出来；只排序则永远有兜底。
 */
object DecoderSelectors {

    /** 硬解优先 = ExoPlayer 默认排序。 */
    val hardwareFirst: MediaCodecSelector = MediaCodecSelector.DEFAULT

    /** 软解优先：同一份候选里把 `softwareOnly` 的排到前面。 */
    val softwareFirst: MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        hardwareFirst.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            .sortedByDescending { info: MediaCodecInfo -> info.softwareOnly }
    }
}
