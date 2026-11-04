package com.veil.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.data.model.Song
import com.veil.music.data.store.LibraryStore
import com.veil.music.data.store.LocalList
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val lists: List<LocalList> = emptyList(),
    val selectedId: String = LibraryStore.LOVE,
    val isLoading: Boolean = true,
) {
    val selected: LocalList? get() = lists.firstOrNull { it.id == selectedId } ?: lists.firstOrNull()
    val songs: List<Song> get() = selected?.songs.orEmpty()
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val store: LibraryStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.load()
            store.state.collect { lib ->
                _state.update { current ->
                    val selected = current.selectedId.takeIf { id -> lib.lists.any { it.id == id } }
                        ?: lib.lists.firstOrNull()?.id.orEmpty()
                    current.copy(lists = lib.lists, selectedId = selected, isLoading = false)
                }
            }
        }
    }

    fun select(id: String) = _state.update { it.copy(selectedId = id) }

    fun createList() {
        viewModelScope.launch {
            val n = _state.value.lists.count { !it.system } + 1
            val id = store.createList("我的歌单 $n")
            _state.update { it.copy(selectedId = id) }
        }
    }

    fun deleteList(id: String) {
        viewModelScope.launch { store.deleteList(id) }
    }

    fun toggleLove(song: Song) {
        viewModelScope.launch { store.toggleLove(song) }
    }

    fun removeFromSelected(songId: String) {
        val id = _state.value.selectedId
        viewModelScope.launch { store.removeFrom(id, songId) }
    }
}
