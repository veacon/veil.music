package com.veil.music.ui.player.fullscreen

import androidx.compose.ui.graphics.Color
import com.veil.music.ui.viewmodel.FsStylePrefs

/**
 * 当前正在唱的那句歌词的字色（墨卷 / 声轨专用）。
 *
 * 默认跟着界面本身的墨色 / 前景色走（[DEFAULT]），其余七档是彩虹色。选的是**当前句**，
 * 已唱过的和还没唱的仍然保持淡墨 —— 否则整屏都是彩色，反而看不出唱到哪。
 *
 * 颜色都压过一档饱和度与亮度：墨卷是宣纸底、声轨有浅色纸面，正红正黄在浅底上会发飘。
 */
enum class LyricAccent(val label: String, val color: Color) {
    /** 跟随界面默认字色，不做任何替换。 */
    DEFAULT("默认", Color.Unspecified),
    RED("赤", Color(0xFFE5484D)),
    ORANGE("橙", Color(0xFFF76B15)),
    YELLOW("黄", Color(0xFFCE9A00)),
    GREEN("绿", Color(0xFF2FA36B)),
    CYAN("青", Color(0xFF0E93A8)),
    BLUE("蓝", Color(0xFF3E63DD)),
    PURPLE("紫", Color(0xFF8E4EC6)),
    ;

    companion object {
        fun of(index: Int): LyricAccent = entries.getOrNull(index) ?: DEFAULT

        fun of(pref: FsStylePrefs): LyricAccent = of(pref.accent)

        /** 界面实际要画的颜色：选了「默认」就交回调用方自己的墨色。 */
        fun resolve(index: Int, fallback: Color): Color =
            of(index).color.takeIf { it != Color.Unspecified } ?: fallback
    }
}
