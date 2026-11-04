package com.veil.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.Song
import com.veil.music.data.model.SourceID
import com.veil.music.data.repository.LxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val source: SourceID? = null,
    val isLoading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val hot: List<String> = emptyList(),
    val error: String? = null,
    val hasSearched: Boolean = false,
)

/**
 * 搜索。
 *
 * [source] 为 null 表示「全部」——四平台并发，结果按平台顺序拼接（与 lx 的聚合搜索一致）。
 * 旧请求在发起新请求前会被取消，避免慢源的旧结果覆盖新词。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: LxRepository,
    prefs: VeilPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(source = prefs.homeSource.value))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var hotLoaded = false

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
    }

    fun setSource(source: SourceID?) {
        _state.update { it.copy(source = source) }
        if (_state.value.query.isNotBlank()) search(_state.value.query)
    }

    fun loadHot() {
        if (hotLoaded) return
        hotLoaded = true
        viewModelScope.launch {
            val columns = repo.hotSearch()
            _state.update { it.copy(hot = columns.flatMap { c -> c.words }.distinct().take(12)) }
        }
    }

    fun search(query: String = _state.value.query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            _state.update { it.copy(songs = emptyList(), hasSearched = false, error = null) }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, query = keyword) }
            val result = runCatching { repo.searchTracks(keyword, _state.value.source) }
            _state.update { st ->
                result.fold(
                    onSuccess = { tracks ->
                        st.copy(isLoading = false, songs = repo.toSongs(tracks), hasSearched = true, error = null)
                    },
                    onFailure = { e ->
                        st.copy(isLoading = false, songs = emptyList(), hasSearched = true, error = e.message ?: "搜索失败")
                    },
                )
            }
        }
    }
}
