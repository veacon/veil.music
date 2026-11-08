package com.veacon.veilmusic.media

import android.content.SharedPreferences
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo

/**
 * 解码器选择策略。
 *
 * 原来只有一条特例：软解模式下 AAC 优先 `OMX.google.aac`，其余完全交给平台返回顺序。
 * 这带来三个问题：
 *  1. 硬解模式下平台可能把「名叫 hardware、实际是固件里最烂的那个」排在前面；
 *  2. 某个 codec 在某个机型上解某个格式必崩时，没有任何屏蔽手段；
 *  3. 崩了之后下次还是选它，形成死循环。
 *
 * 这里补上：排序策略（硬解优先 / 软解优先）+ 设备级黑名单（落盘）+ 失败反馈闭环。
 * 黑名单按「机型 | mime | codec 名」存，换机型不会误伤。
 */
internal class DecoderPolicy(
    private val prefs: SharedPreferences,
    private val deviceTag: String = android.os.Build.MODEL ?: "unknown",
) {

    /** 最近一次给某个 mime 选中的 codec —— 失败时用它精准拉黑。 */
    private val lastChosen = HashMap<String, String>()

    /**
     * [preferHardware] = true 走硬解排序；false 走软解排序（配合 FFmpeg 扩展优先）。
     * 被拉黑的 codec 会被排到最后而不是删掉：真到了没有别的解码器时，宁可试一次也别让格式彻底不可播。
     */
    fun select(
        infos: List<MediaCodecInfo>,
        mimeType: String,
        preferHardware: Boolean,
        log: (String) -> Unit = {},
    ): List<MediaCodecInfo> {
        if (infos.size <= 1) {
            infos.firstOrNull()?.let { lastChosen[mimeType] = it.name }
            return infos
        }
        val blocked = blockedFor(mimeType)
        if (blocked.isNotEmpty()) {
            log("decoder=$mimeType blacklisted=${blocked.joinToString(",")}")
        }
        val ranker = if (preferHardware) HARDWARE_FIRST else SOFTWARE_FIRST
        val ranked = infos.sortedWith(
            // 先按策略排序，再把黑名单压到末尾（稳定排序保留组内顺序）。
            compareBy<MediaCodecInfo> { it.name in blocked }.then(ranker),
        )
        ranked.firstOrNull()?.let { lastChosen[mimeType] = it.name }
        return ranked
    }

    /** 解码失败时调用：把当前 mime 上次选中的 codec 拉黑，重启后仍然生效。 */
    fun reportFailure(mimeType: String, codecName: String? = lastChosen[mimeType]) {
        val name = codecName ?: return
        if (name.isBlank()) return
        val entry = key(mimeType, name)
        val current = blacklist()
        if (entry in current) return
        // 只保留最近 32 条，避免长期使用后 prefs 无限增长。
        val trimmed = (current + entry).toList().takeLast(MAX_BLACKLIST).toSet()
        prefs.edit().putStringSet(KEY_BLACKLIST, trimmed).apply()
        lastChosen.remove(mimeType)
    }

    fun blockedFor(mimeType: String): Set<String> =
        blacklist().asSequence()
            .filter { it.startsWith("$deviceTag|$mimeType|") }
            .map { it.substringAfterLast('|') }
            .toSet()

    fun snapshot(): String =
        blacklist().joinToString(",").ifBlank { "none" }

    private fun key(mimeType: String, codecName: String) = "$deviceTag|$mimeType|$codecName"

    private fun blacklist(): Set<String> =
        runCatching { prefs.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())

    private companion object {
        const val KEY_BLACKLIST = "codec_blacklist"
        const val MAX_BLACKLIST = 32

        /** 硬解：真·硬件优先，其次厂商实现，最后才是软件解码器。 */
        val HARDWARE_FIRST: Comparator<MediaCodecInfo> =
            compareByDescending<MediaCodecInfo> { it.hardwareAccelerated && !it.softwareOnly }
                .thenByDescending { !it.softwareOnly }
                .thenByDescending { it.vendor }

        /**
         * 软解：软件实现优先，AAC 继续优先 OMX.google.aac（这台机器上
         * c2.android.aac 在 HE-AAC 分段边界会吞样本，之前专门绕开过）。
         */
        val SOFTWARE_FIRST: Comparator<MediaCodecInfo> =
            compareByDescending<MediaCodecInfo> { it.softwareOnly }
                .thenByDescending { it.name.startsWith("OMX.google.aac") }
                .thenByDescending { it.name.startsWith("c2.android") }
    }
}
