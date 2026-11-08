package com.veacon.veilmusic.media

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * 输出层定标（P2）。
 *
 * 两项都是「有就提升、没有也不影响播放」的可选能力：
 *
 *  1. **统一输出采样率**：曲库里 44.1k 与 48k 混着放，每次采样率变化 `AudioTrack` 都要重建一次
 *     sink，听感是换歌处一下几十毫秒的空档，偶尔还带一声轻爆。把输出统一到设备原生采样率
 *     （电视基本是 48k）之后，sink 全程只建一次。重采样走 media3 自带的 Sonic，无额外依赖。
 *  2. **音频 offload**：解码交给 DSP，CPU 与功耗双降。代价是整条自定义处理器链必须让路
 *     （音量均衡、律动分析都没有了），而且只有 Android 10+ 的设备才报得出来支不支持 ——
 *     所以它是「探测得到才出现」的实验开关，默认关闭。
 */
object OutputTuning {

    /** 设备原生输出采样率；拿不到返回 0（= 不重采样，保持原样）。 */
    fun deviceSampleRate(context: Context): Int = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.coerceIn(44_100, 192_000) ?: 0
    }.getOrDefault(0)

    /**
     * offload 能不能用。
     *
     * 系统级的 `isOffloadedPlaybackSupported` 至今仍是 SystemApi（第三方应用调不到），所以这里
     * 只能按平台版本给一个保守判断：Android 10 起才把 offload 放进公开的音频管线。
     * 真到了不支持的设备上，media3 自己的能力探测会让 offload 自动退回普通输出，不会没声音。
     */
    fun offloadSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 启动日志用：一眼看出这次是以什么输出形态跑的。 */
    fun summary(unify: Boolean, offload: Boolean, sampleRate: Int): String = buildString {
        append("out=")
        append(if (unify && sampleRate > 0) "${sampleRate}Hz" else "native")
        append(" offload=")
        append(if (offload) "on" else "off")
    }
}
