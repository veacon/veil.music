package com.veacon.veilmusic.ui.guide

import androidx.lifecycle.ViewModel
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 新手引导页的演示曲目：进入引导页时自动播放《爱人错过》，让左侧真实播放页不是空态。
 *
 * 走「按歌名 + 歌手搜真实曲目」而不是拿内置假数据：
 * 落雪解析（[com.veacon.veilmusic.luoxue.TrackMatcher]）按「歌名 / 歌手 / 专辑 / **时长**」
 * 打分，假数据的时长（58s）会把正确匹配的分数拉低，甚至判为不可接受。
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val repo: MusicRepository,
) : ViewModel() {

    /** 搜演示曲目；搜不到或失败返回 null（引导页照常可用，只是左侧播放页保持空态）。 */
    suspend fun demoSong(): Song? = runCatching {
        val results = repo.search(DEMO_TERM, limit = 20).getOrNull() ?: return null
        // 优先取标题就是《爱人错过》的那首 —— 搜「爱人错过 告五人」时首条可能是同歌手别的歌。
        results.songs.firstOrNull { it.title.contains(DEMO_TITLE) } ?: results.songs.firstOrNull()
    }.getOrNull()

    private companion object {
        /** 演示曲目：告五人《爱人错过》。 */
        const val DEMO_TERM = "爱人错过 告五人"
        const val DEMO_TITLE = "爱人错过"
    }
}
