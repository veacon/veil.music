package com.veil.music.ui.navigation

/**
 * 顶部标签。顺序即显示顺序：正在播放 / 发现 / 我的 / 搜索 / 设置。
 *
 * 「正在播放」回到胶囊链里，并且是**第一枚**：左右键走到它就跟其它 tab 一样切页
 * （具体路由映射在 AppShell 的 navigateTab 里）。播放页本身仍是沉浸式全屏，
 * 进入后顶栏不渲染，按返回键回到发现页。
 */
enum class TopNavTab(val label: String) {
    NowPlaying("正在播放"),
    Discover("发现"),
    Library("我的"),
    Search("搜索"),
    Settings("设置"),
}
