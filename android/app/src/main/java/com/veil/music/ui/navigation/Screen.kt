package com.veil.music.ui.navigation

import java.net.URLEncoder

/** 应用内路由。只有五个主页面 + 一个「歌单/榜单详情」。 */
sealed class Screen(val route: String) {
    object Discover   : Screen("discover")
    object Library    : Screen("library")
    object Search     : Screen("search")
    object NowPlaying : Screen("now_playing")
    object Settings   : Screen("settings")

    /** 歌单与榜单共用一个详情页，id 里的前缀区分（见 LxIds）。 */
    object Collection : Screen("collection/{collectionId}/{title}/{artwork}") {
        fun route(id: String, title: String, artworkUrl: String = "") =
            "collection/${encode(id)}/${encode(title)}/${encode(artworkUrl)}"

        private fun encode(s: String) = URLEncoder.encode(s.ifEmpty { " " }, "UTF-8")
    }
}
