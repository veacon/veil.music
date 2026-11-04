package com.veil.music.data.store

import android.content.Context
import com.veil.music.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 一个本地歌单。系统歌单（我喜欢 / 播放历史）不可删除、不可改名。 */
data class LocalList(
    val id: String,
    val name: String,
    val songs: List<Song> = emptyList(),
    val system: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class LibraryState(
    val lists: List<LocalList> = emptyList(),
) {
    val loved: LocalList? get() = lists.firstOrNull { it.id == LibraryStore.LOVE }
    val history: LocalList? get() = lists.firstOrNull { it.id == LibraryStore.HISTORY }
    val userLists: List<LocalList> get() = lists.filter { !it.system }
}

/**
 * 本地曲库：「我喜欢」「播放历史」+ 用户自建歌单。
 *
 * Apple Music 版的曲库是账号数据；换成 lx 音源后没有账号体系，所以曲库落在本机
 * （filesDir/library.json）。删除应用即清空，符合「无账号、纯本地」的定位。
 */
@Singleton
class LibraryStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val file = File(context.filesDir, "library.json")

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val parsed = runCatching { parse(file.takeIf { it.exists() }?.readText()) }.getOrNull()
        _state.value = LibraryState(normalize(parsed ?: emptyList()))
    }

    fun isLoved(songId: String): Boolean =
        _state.value.loved?.songs?.any { it.id == songId } == true

    suspend fun toggleLove(song: Song): Boolean {
        val loved = _state.value.loved ?: LocalList(LOVE, "我喜欢", system = true)
        val exists = loved.songs.any { it.id == song.id }
        val songs = if (exists) loved.songs.filterNot { it.id == song.id } else listOf(song) + loved.songs
        replace(loved.copy(songs = songs, updatedAt = System.currentTimeMillis()))
        return !exists
    }

    /** 播放历史：同一首只保留最近一次，最多 300 条。 */
    suspend fun addHistory(song: Song) {
        val history = _state.value.history ?: LocalList(HISTORY, "播放历史", system = true)
        val songs = (listOf(song) + history.songs.filterNot { it.id == song.id }).take(MAX_HISTORY)
        replace(history.copy(songs = songs, updatedAt = System.currentTimeMillis()))
    }

    suspend fun createList(name: String): String {
        val id = UUID.randomUUID().toString()
        replace(LocalList(id, name.ifBlank { "新建歌单" }))
        return id
    }

    suspend fun renameList(id: String, name: String) {
        val list = _state.value.lists.firstOrNull { it.id == id } ?: return
        if (list.system) return
        replace(list.copy(name = name.ifBlank { list.name }))
    }

    suspend fun addTo(listId: String, songs: List<Song>) {
        val list = _state.value.lists.firstOrNull { it.id == listId } ?: return
        val existing = list.songs.map { it.id }.toSet()
        val merged = list.songs + songs.filterNot { it.id in existing }
        replace(list.copy(songs = merged, updatedAt = System.currentTimeMillis()))
    }

    suspend fun removeFrom(listId: String, songId: String) {
        val list = _state.value.lists.firstOrNull { it.id == listId } ?: return
        replace(list.copy(songs = list.songs.filterNot { it.id == songId }))
    }

    suspend fun deleteList(id: String) {
        val list = _state.value.lists.firstOrNull { it.id == id } ?: return
        if (list.system) return
        _state.value = LibraryState(_state.value.lists.filterNot { it.id == id })
        persist()
    }

    private suspend fun replace(list: LocalList) {
        val lists = _state.value.lists.toMutableList()
        val idx = lists.indexOfFirst { it.id == list.id }
        if (idx >= 0) lists[idx] = list else lists.add(list)
        _state.value = LibraryState(lists)
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(serialize(_state.value.lists))
        }
    }

    // ── 序列化 ─────────────────────────────────────────────────────────────

    private fun normalize(lists: List<LocalList>): List<LocalList> {
        val byId = lists.associateBy { it.id }.toMutableMap()
        if (LOVE !in byId) byId[LOVE] = LocalList(LOVE, "我喜欢", system = true)
        if (HISTORY !in byId) byId[HISTORY] = LocalList(HISTORY, "播放历史", system = true)
        return listOf(byId.getValue(LOVE), byId.getValue(HISTORY)) +
            byId.values.filter { it.id != LOVE && it.id != HISTORY }
    }

    private fun serialize(lists: List<LocalList>): String {
        val arr = JSONArray()
        lists.forEach { list ->
            val songs = JSONArray()
            list.songs.forEach { songs.put(songToJson(it)) }
            arr.put(
                JSONObject()
                    .put("id", list.id)
                    .put("name", list.name)
                    .put("system", list.system)
                    .put("updatedAt", list.updatedAt)
                    .put("songs", songs)
            )
        }
        return arr.toString()
    }

    private fun parse(raw: String?): List<LocalList> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val songsArr = o.optJSONArray("songs") ?: JSONArray()
            val songs = (0 until songsArr.length()).mapNotNull { j ->
                songsArr.optJSONObject(j)?.let { songFromJson(it) }
            }
            LocalList(
                id = o.optString("id"),
                name = o.optString("name"),
                songs = songs,
                system = o.optBoolean("system"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }

    private fun songToJson(song: Song): JSONObject = JSONObject()
        .put("id", song.id)
        .put("title", song.title)
        .put("artistName", song.artistName)
        .put("albumName", song.albumName)
        .put("durationMs", song.durationMs)
        .put("artworkUrl", song.artworkUrl ?: "")

    private fun songFromJson(o: JSONObject): Song = Song(
        id = o.optString("id"),
        title = o.optString("title"),
        artistName = o.optString("artistName"),
        albumName = o.optString("albumName"),
        durationMs = o.optLong("durationMs"),
        artworkUrl = o.optString("artworkUrl").ifBlank { null },
    )

    companion object {
        const val LOVE = "love"
        const val HISTORY = "history"
        private const val MAX_HISTORY = 300
    }
}
