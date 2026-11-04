package com.veil.music.ui.navigation

/** 顶部标签。顺序即显示顺序：发现 / 我的 / 搜索 / 正在播放 / 设置。 */
enum class TopNavTab(val label: String) {
    Discover("发现"),
    Library("我的"),
    Search("搜索"),
    NowPlaying("正在播放"),
    Settings("设置"),
}
