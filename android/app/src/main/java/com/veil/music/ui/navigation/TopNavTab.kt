package com.veil.music.ui.navigation

/**
 * 顶部标签。顺序即显示顺序：发现 / 我的 / 搜索 / 设置。
 *
 * 原来这里还有一枚「正在播放」，点进去是整页播放器。它挤在导航链里，左右键切页时
 * 常常被顺手带过去，焦点也老跟页面抢；现在改成屏幕底部那条迷你播放条（见 MiniPlayerCapsule），
 * 播不播放、切不切歌都在原地完成，只有按「确定」落在中间封面那块才进完整播放页。
 */
enum class TopNavTab(val label: String) {
    Discover("发现"),
    Library("我的"),
    Search("搜索"),
    Settings("设置"),
}
