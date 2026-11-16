package com.veacon.veilmusic.ui.viewmodel

import com.veacon.veilmusic.data.network.LyricLine
import com.squareup.moshi.JsonClass

/**
 * V84 上游：当前播放歌曲的歌词快照，随每次歌词加载落盘。
 *
 * App 重启恢复续播时，先用这份快照把歌词画出来（此刻 Apple 接口还没返回），
 * 再由正常的 loadLyrics 刷新 —— 修复「重启后播放页歌词空白/闪一下」。
 *
 * [source] 同时保存歌词来源标签（网易云/QQ/…），让恢复瞬间「歌词来源」也不显示成「—」。
 */
@JsonClass(generateAdapter = true)
internal data class SavedLyrics(val songId: String, val lines: List<LyricLine>, val source: String? = null)

internal fun SavedLyrics?.linesFor(songId: String): List<LyricLine>? =
    this?.takeIf { it.songId == songId && it.lines.isNotEmpty() }?.lines
