package com.veil.music.ui.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.data.model.Song
import com.veil.music.data.repository.LxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionState(
    val isLoading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: LxRepository,
) : ViewModel() {

    private val collectionId: String = savedState.get<String>("collectionId").orEmpty()

    private val _state = MutableStateFlow(CollectionState())
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = CollectionState(isLoading = true)
            val result = runCatching { repo.tracksForCollection(collectionId) }
            _state.value = result.fold(
                onSuccess = { tracks ->
                    CollectionState(
                        isLoading = false,
                        songs = repo.toSongs(tracks),
                        error = if (tracks.isEmpty()) "这个歌单没有取到歌曲" else null,
                    )
                },
                onFailure = { CollectionState(isLoading = false, error = it.message ?: "加载失败") },
            )
        }
    }
}
