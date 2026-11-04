package com.veil.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.data.model.SonglistInfo
import com.veil.music.data.model.SourceID
import com.veil.music.data.repository.LxRepository
import com.veil.music.data.store.LibraryStore
import com.veil.music.media.InAppWebServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    webServer: InAppWebServer,
    private val library: LibraryStore,
    private val repo: LxRepository,
) : ViewModel() {
    private val _goToNowPlaying = MutableStateFlow(false)
    val goToNowPlaying: StateFlow<Boolean> = _goToNowPlaying

    // 手机管理页推来的搜索关键词：收到即跳到搜索页（SearchViewModel 负责真正发起搜索）。
    private val _searchKeyword = MutableStateFlow<String?>(null)
    val searchKeyword: StateFlow<String?> = _searchKeyword

    /** 手机端操作的反馈（建歌单 / 导入歌单链接），由 AppShell 转成 Toast。 */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            webServer.searchRequests.collect { kw ->
                _searchKeyword.value = kw
            }
        }
        // 添加歌单：手机输入法起名，直接建一个本地歌单。
        viewModelScope.launch {
            webServer.playlistRequests.collect { name ->
                runCatching { library.createList(name) }
                    .onSuccess { _messages.emit("已创建歌单：$name") }
                    .onFailure { _messages.emit("创建歌单失败：${it.message}") }
            }
        }
        // 推送歌单链接：识别平台 → 拉曲目 → 建成一个本地歌单。
        viewModelScope.launch {
            webServer.songlistRequests.collect { raw ->
                _messages.emit(importSonglist(raw))
            }
        }
    }

    /**
     * 把一条分享链接（或纯 ID）变成本地歌单。
     *
     * 平台识别只看域名，取 ID 各平台规则不同：
     *  - 网易云 `music.163.com/playlist?id=xxx` → 第一个长数字
     *  - QQ音乐 `y.qq.com/.../playlist/xxx` → 路径里的数字（disstid）
     *  - 酷狗 `kugou.com/.../special/single/xxx.html` → 第一个长数字
     *  - 酷我 `kuwo.cn/playlist_detail/xxx` → 第一个长数字
     * 认不出域名但整串是数字时，按网易云处理（最常见）。
     */
    private suspend fun importSonglist(raw: String): String {
        val ref = parseSonglistRef(raw) ?: return "无法识别歌单链接：$raw"
        val (source, platformId) = ref
        val tracks = runCatching {
            repo.songlistDetail(SonglistInfo(id = platformId, source = source, name = ""))
        }.getOrElse { return "拉取歌单失败：${it.message}" }
        if (tracks.isEmpty()) return "歌单里没有取到曲目（可能已下架或需要 VIP）"
        val songs = repo.toSongs(tracks)
        val listId = library.createList("${source.displayName}歌单")
        library.addTo(listId, songs)
        return "已导入 ${source.displayName} 歌单，共 ${songs.size} 首"
    }

    private fun parseSonglistRef(raw: String): Pair<SourceID, String>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val longDigits = Regex("""(\d{4,})""")
        return when {
            s.contains("music.163.com") || s.contains("163cn.tv") ->
                longDigits.find(s)?.let { SourceID.WY to it.groupValues[1] }
            s.contains("y.qq.com") || s.contains("c.y.qq.com") ->
                (Regex("""playlist/(\d+)""").find(s) ?: longDigits.find(s))
                    ?.let { SourceID.TX to it.groupValues[1] }
            s.contains("kugou.com") ->
                longDigits.find(s)?.let { SourceID.KG to it.groupValues[1] }
            s.contains("kuwo.cn") ->
                longDigits.find(s)?.let { SourceID.KW to it.groupValues[1] }
            s.matches(Regex("""\d{4,}""")) -> SourceID.WY to s
            else -> null
        }
    }

    fun consumeSearchKeyword() { _searchKeyword.value = null }

    // Toggles queue/lyrics panel in NowPlaying screen
    private val _toggleQueue = MutableStateFlow(0)
    val toggleQueue: StateFlow<Int> = _toggleQueue

    // MainActivity sets this so Menu key knows current screen
    var isOnNowPlaying: Boolean = false

    // When a fullscreen music video is on screen, MainActivity must NOT eat media/menu
    // keys for the audio player — the video screen owns transport, and Menu shouldn't
    // jump to Now Playing.
    var isOnMusicVideo: Boolean = false

    fun navigateToNowPlaying() { _goToNowPlaying.value = true }
    fun consumeNowPlayingNavigation() { _goToNowPlaying.value = false }
    fun toggleQueuePanel() { _toggleQueue.value++ }
}
