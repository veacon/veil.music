package com.veil.music.data.repository

import com.veil.music.data.lx.LxIds
import com.veil.music.data.lx.LxMapper
import com.veil.music.data.lx.TrackRegistry
import com.veil.music.data.model.Album
import com.veil.music.data.model.BoardInfo
import com.veil.music.data.model.LyricLine
import com.veil.music.data.model.LyricsPayload
import com.veil.music.data.model.Song
import com.veil.music.data.model.SonglistInfo
import com.veil.music.data.model.SonglistTag
import com.veil.music.data.model.SourceID
import com.veil.music.data.model.Track
import com.veil.music.lyrics.LyricParser
import com.veil.music.lyrics.LyricsMapper
import com.veil.music.source.SourceManager
import com.veil.music.source.builtin.BuiltInLyricResolver
import com.veil.music.source.catalog.Boards
import com.veil.music.source.catalog.Catalogs
import com.veil.music.source.catalog.HotSearch
import com.veil.music.source.catalog.HotSearchColumn
import com.veil.music.source.catalog.Songlists
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 音源数据的唯一入口。
 *
 * 职责边界：
 *  - 只跟 [Songlists]/[Boards]/[Catalogs]（直连目录）与 [SourceManager]（脚本）打交道；
 *  - 出去的一律是 UI 模型（[Song] / [Album]）或已登记的 [Track]；
 *  - 不做缓存策略（那是 HomeStore 与 PlaybackResolver 的事），不做线程切换（调用方在 IO 上调）。
 */
@Singleton
class LxRepository @Inject constructor(
    private val catalogs: Catalogs,
    private val songlists: Songlists,
    private val boards: Boards,
    private val hotSearch: HotSearch,
    private val sourceManager: SourceManager,
    private val builtInLyrics: BuiltInLyricResolver,
    private val registry: TrackRegistry,
) {
    // ── 歌单广场 ────────────────────────────────────────────────────────────

    suspend fun recommendedSonglists(source: SourceID, page: Int = 1): List<SonglistInfo> {
        val service = songlists.serviceFor(source) ?: return emptyList()
        val order = service.orders.firstOrNull() ?: return emptyList()
        return runCatching { service.fetchRecommended(order, SonglistTag.ALL, page) }.getOrDefault(emptyList())
    }

    suspend fun songlistsByOrder(source: SourceID, orderId: String, tagId: String, page: Int): List<SonglistInfo> {
        val service = songlists.serviceFor(source) ?: return emptyList()
        val order = service.orders.firstOrNull { it.id == orderId } ?: service.orders.firstOrNull() ?: return emptyList()
        val tag = if (tagId.isEmpty()) SonglistTag.ALL else SonglistTag(tagId, tagId)
        return runCatching { service.fetchRecommended(order, tag, page) }.getOrDefault(emptyList())
    }

    suspend fun songlistOrders(source: SourceID) = songlists.serviceFor(source)?.orders.orEmpty()

    suspend fun songlistTags(source: SourceID) =
        runCatching { songlists.serviceFor(source)?.fetchTags().orEmpty() }.getOrDefault(emptyList())

    suspend fun songlistDetail(info: SonglistInfo): List<Track> {
        val service = songlists.serviceFor(info.source) ?: return emptyList()
        return runCatching { service.fetchDetail(info).tracks }.getOrDefault(emptyList())
    }

    suspend fun searchSonglists(source: SourceID, keyword: String, page: Int = 1): List<SonglistInfo> =
        runCatching { songlists.serviceFor(source)?.search(keyword, page).orEmpty() }.getOrDefault(emptyList())

    // ── 榜单 ───────────────────────────────────────────────────────────────

    suspend fun boards(source: SourceID): List<BoardInfo> =
        runCatching { boards.serviceFor(source)?.fetchBoards().orEmpty() }.getOrDefault(emptyList())

    suspend fun boardTracks(source: SourceID, bangid: String, page: Int = 1): List<Track> =
        runCatching { boards.serviceFor(source)?.fetchTracks(bangid, page).orEmpty() }.getOrDefault(emptyList())

    // ── 搜索 ───────────────────────────────────────────────────────────────

    /** [source] 为 null 时四平台并发聚合（对应 lx 的「全部」搜索）。 */
    suspend fun searchTracks(keyword: String, source: SourceID? = null, page: Int = 1): List<Track> {
        if (keyword.isBlank()) return emptyList()
        return if (source == null) {
            runCatching { catalogs.searchAll(keyword, page) }.getOrDefault(emptyList())
        } else {
            runCatching { catalogs.search(source, keyword, page) }.getOrDefault(emptyList())
        }
    }

    suspend fun hotSearch(): List<HotSearchColumn> =
        runCatching { hotSearch.fetchAll() }.getOrDefault(emptyList())

    // ── 详情页取曲目（歌单 / 榜单统一入口）────────────────────────────────

    suspend fun tracksForCollection(id: String): List<Track> {
        val parsed = LxIds.parse(id) ?: return emptyList()
        return when (parsed.kind) {
            LxIds.KIND_BOARD -> boardTracks(parsed.source, parsed.platformId)
            LxIds.KIND_SONGLIST -> songlistDetail(
                SonglistInfo(id = parsed.platformId, source = parsed.source, name = "")
            )
            else -> emptyList()
        }
    }

    /** 登记 + 映射成 UI 模型；界面拿到的每一个 Song 都保证能在 [TrackRegistry] 里查到。 */
    fun toSongs(tracks: List<Track>): List<Song> {
        registry.putAll(tracks)
        return LxMapper.toSongs(tracks)
    }

    fun songlistCards(items: List<SonglistInfo>): List<Album> = items.map(LxMapper::songlistToAlbum)

    fun boardCards(items: List<BoardInfo>): List<Album> = items.map(LxMapper::boardToAlbum)

    // ── 歌词 ───────────────────────────────────────────────────────────────

    /**
     * 歌词链路与 lx 一致：先问脚本（`lyric` action），没有再走内置直连，
     * 最后按「歌名 + 歌手」跨平台兜底搜一遍。
     */
    suspend fun lyrics(track: Track): LyricsPayload {
        scriptLyrics(track)?.let { return LyricsPayload(it, "脚本") }
        runCatching { builtInLyrics.fetch(track) }.getOrNull()?.let {
            return LyricsPayload(LyricsMapper.toUi(it), track.source.displayName)
        }
        runCatching { builtInLyrics.searchByName(track) }.getOrNull()?.let {
            return LyricsPayload(LyricsMapper.toUi(it), "跨平台")
        }
        return LyricsPayload()
    }

    private suspend fun scriptLyrics(track: Track): List<LyricLine>? {
        val json = runCatching { sourceManager.requestLyric(track) }.getOrNull() ?: return null
        val raw = json.optString("lyric", "")
        if (raw.isBlank()) return null
        val translation = json.optString("tlyric", "")
        val parsed = runCatching { LyricParser.parseAuto(raw, translation.ifBlank { null }) }.getOrNull()
            ?: return null
        return LyricsMapper.toUi(parsed).ifEmpty { null }
    }

    // ── 脚本能力 ───────────────────────────────────────────────────────────

    fun availableQualities(source: SourceID) = sourceManager.availableQualities(source)

    fun supportedSources(): List<SourceID> = sourceManager.supportedSources()

    fun hasScriptFor(source: SourceID): Boolean = sourceManager.supportedSources().contains(source)
}
