package com.veil.music.ui.discover

import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Album
import com.veil.music.data.model.SourceID
import com.veil.music.data.repository.LxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 首页的一行（与旧 HomeSection 同构，卡片仍复用 AlbumCard）。 */
data class DiscoverSection(
    val title: String,
    val albums: List<Album>,
    val style: String? = null,
)

data class DiscoverState(
    val isLoading: Boolean = false,
    val source: SourceID = VeilPreferences.DEFAULT_HOME_SOURCE,
    val hero: Album? = null,
    val sections: List<DiscoverSection> = emptyList(),
    val error: String? = null,
)

/**
 * 发现页数据源（首页）。
 *
 * 单一数据源模型：设置里选中的那个平台决定整页内容 —— 英雄位取该源推荐歌单的第一张，
 * 「推荐歌单」是剩余部分，「排行榜」是该源的榜单列表。切源 = 换 key 重新拉，旧源的
 * 结果留在内存缓存里，切回来是瞬时的。
 *
 * 之所以把聚合逻辑放在 Store 而不是 ViewModel：切源时旧请求必须被取消，
 * 否则慢的那个源返回后会把新源的界面覆盖掉（老版本最典型的串数据 bug）。
 */
@Singleton
class HomeStore @Inject constructor(
    private val repo: LxRepository,
    private val prefs: VeilPreferences,
) {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private val cache = mutableMapOf<SourceID, DiscoverState>()

    private var lastLoaded: SourceID? = null

    fun loadIfNeeded(source: SourceID, force: Boolean = false) {
        if (!force && lastLoaded == source && _state.value.sections.isNotEmpty()) return
        load(source, force)
    }

    fun refresh() = load(prefs.homeSource.value, force = true)

    private fun load(source: SourceID, force: Boolean) {
        loadJob?.cancel()
        if (!force) {
            cache[source]?.let {
                lastLoaded = source
                _state.value = it
                return
            }
        }
        lastLoaded = source
        _state.value = DiscoverState(isLoading = true, source = source, sections = emptyList())

        loadJob = scope.launch {
            // 网络、JSON 解析、卡片映射、状态构造整块放 IO：以前只有 HTTP 在 IO，
            // 解析回到 Main，切到发现页时会在主线程构建整页数据。
            // cache / StateFlow 的写回仍留在 Main，避免引入锁。
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val (lists, boards) = coroutineScope {
                        val listsDeferred = async { repo.recommendedSonglists(source) }
                        val boardsDeferred = async { repo.boards(source) }
                        listsDeferred.await() to boardsDeferred.await()
                    }
                    val cards = repo.songlistCards(lists)
                    val boardCards = repo.boardCards(boards)
                    val hero = cards.firstOrNull()
                    val sections = buildList {
                        val rest = cards.drop(1)
                        if (rest.isNotEmpty()) add(DiscoverSection("推荐歌单", rest.take(18)))
                        if (boardCards.isNotEmpty()) add(DiscoverSection("排行榜", boardCards.take(18)))
                    }
                    DiscoverState(
                        isLoading = false,
                        source = source,
                        hero = hero,
                        sections = sections,
                        error = if (sections.isEmpty() && hero == null) "该数据源暂时没有返回内容" else null,
                    )
                }
            }

            result.fold(
                onSuccess = { next ->
                    cache[source] = next
                    // 只有仍然是当前源时才写回，防止慢请求覆盖新选择
                    if (lastLoaded == source) _state.value = next
                },
                onFailure = { e ->
                    if (lastLoaded == source) {
                        _state.update {
                            it.copy(isLoading = false, error = e.message ?: "加载失败")
                        }
                    }
                },
            )
        }
    }

    /** 设置里切源后调用：清掉内存缓存，保证拿到的是新源的内容。 */
    fun onSourceChanged(source: SourceID) {
        lastLoaded = null
        _state.value = DiscoverState(isLoading = true, source = source)
        load(source, force = true)
    }
}
