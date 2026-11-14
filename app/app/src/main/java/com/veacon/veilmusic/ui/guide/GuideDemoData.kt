package com.veacon.veilmusic.ui.guide

import com.veacon.veilmusic.data.network.LyricLine
import com.veacon.veilmusic.data.network.LyricWord
import com.veacon.veilmusic.data.model.Song

/**
 * 新手引导页的内置演示数据。
 *
 * 全部为**本地静态数据**：封面读取 assets（`file:///android_asset/guide/cover.webp`），
 * 歌词与时间轴内置在代码里 —— 断网进入引导页也能秒显，绝不发起网络请求。
 *
 * 演示曲目：告五人《爱人错过》（专辑《我肯定在几百年前就说过爱你》）。
 * 歌词取第一段完整结构（主歌×2 + 副歌，00:46.190 → 01:44.810），引导页预览按此区间循环推进。
 *
 * 每行只给一个覆盖整行的 [LyricWord]：渲染层的 [com.veacon.veilmusic.ui.screens.lyricCharSpans]
 * 会对中日韩文本自动按字符均分时长，逐字扫光效果与真实逐字歌词一致。
 */
object GuideDemoData {

    /** 预览循环区间起点（ms）。 */
    const val LOOP_START_MS = 46_190L

    /** 预览循环区间长度（ms）——最后一行结束减去第一行开始。 */
    const val LOOP_DURATION_MS = 104_810L - LOOP_START_MS

    /** assets 封面图路径（Coil 可直接加载）。 */
    const val COVER_URI = "file:///android_asset/guide/cover.webp"

    /** 演示歌曲（封面指向本地 assets，无网络依赖）。 */
    val song = Song(
        id = "guide-demo-song",
        title = "爱人错过",
        artistName = "告五人",
        albumName = "我肯定在几百年前就说过爱你",
        durationMs = LOOP_DURATION_MS,
        artworkUrl = COVER_URI,
        artworkBgColor = null,
        previewUrl = null,
        hasLyrics = true,
    )

    /** 演示专辑（第三页首页卡片流用）。 */
    val album = com.veacon.veilmusic.data.model.Album(
        id = "guide-demo-album",
        title = "我肯定在几百年前就说过爱你",
        artistName = "告五人",
        artworkUrl = COVER_URI,
    )

    /** 简体歌词（时间轴来自公开 LRC，第一段完整结构）。 */
    val lyricsSimplified: List<LyricLine> = listOf(
        line(46_190, 52_600, "我肯定在几百年前就说过爱你"),
        line(52_600, 59_848, "只是你忘了 我也没记起"),
        line(59_848, 66_376, "我肯定在几百年前就说过爱你"),
        line(66_376, 73_659, "只是你忘了 我也没记起"),
        line(73_659, 77_028, "走过 路过 没遇过"),
        line(77_028, 79_998, "回头 转头 还是错"),
        line(79_998, 85_215, "你我不曾感受过 相撞在街口"),
        line(85_215, 87_120, "相撞在街口"),
        line(87_120, 90_519, "你妈没有告诉你"),
        line(90_519, 94_078, "撞到人要说对不起"),
        line(94_078, 97_209, "本来今天好好的"),
        line(97_209, 99_078, "爱人就错过"),
        line(99_078, 104_810, "爱人就错过"),
    )

    /** 繁体歌词（与简体同一时间轴，仅字形不同）。 */
    val lyricsTraditional: List<LyricLine> = listOf(
        line(46_190, 52_600, "我肯定在幾百年前就說過愛你"),
        line(52_600, 59_848, "只是你忘了 我也沒記起"),
        line(59_848, 66_376, "我肯定在幾百年前就說過愛你"),
        line(66_376, 73_659, "只是你忘了 我也沒記起"),
        line(73_659, 77_028, "走過 路過 沒遇過"),
        line(77_028, 79_998, "回頭 轉頭 還是錯"),
        line(79_998, 85_215, "你我不曾感受過 相撞在街口"),
        line(85_215, 87_120, "相撞在街口"),
        line(87_120, 90_519, "你媽沒有告訴你"),
        line(90_519, 94_078, "撞到人要說對不起"),
        line(94_078, 97_209, "本來今天好好的"),
        line(97_209, 99_078, "愛人就錯過"),
        line(99_078, 104_810, "愛人就錯過"),
    )

    /** 按引导页当前「界面语言」返回对应字形的歌词（繁体只在中文界面下展示差异）。 */
    fun lyrics(script: com.veacon.veilmusic.data.LyricsScript): List<LyricLine> = when (script) {
        com.veacon.veilmusic.data.LyricsScript.TRADITIONAL -> lyricsTraditional
        else -> lyricsSimplified
    }

    private fun line(startMs: Long, endMs: Long, text: String) = LyricLine(
        startMs = startMs,
        endMs = endMs,
        text = text,
        words = listOf(LyricWord(startMs = startMs, endMs = endMs, text = text)),
    )
}
