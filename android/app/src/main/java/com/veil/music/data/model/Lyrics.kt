package com.veil.music.data.model

/**
 * 播放页使用的歌词模型（毫秒时间轴 + 逐字切片）。
 *
 * 音源侧解析出来的行级/逐字数据统一在 `lyrics/LyricsMapper` 里转换成这套结构，
 * Now Playing 的歌词引擎只认这里，不再关心歌词来自脚本、直连还是 LRCLIB。
 */
data class LyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 背景人声（和声）片段。沿用原引擎的结构，音源暂无该数据时保持 null。 */
data class LyricBackground(
    val startMs: Long,
    val endMs: Long = 0L,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long = 0L,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val background: LyricBackground? = null,
)

/** 歌词来源，仅用于 Now Playing 角标与排障。 */
data class LyricsPayload(
    val lines: List<LyricLine> = emptyList(),
    val source: String? = null,
)
