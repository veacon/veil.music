package com.veil.music.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.veil.music.data.VeilPreferences
import com.veil.music.data.model.SourceID
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val store: HomeStore,
    prefs: VeilPreferences,
) : ViewModel() {

    val state: StateFlow<DiscoverState> = store.state
    val homeSource: StateFlow<SourceID> = prefs.homeSource

    init {
        store.loadIfNeeded(prefs.homeSource.value)
        // 设置里切源 → 立刻重载；drop(1) 跳过订阅时重放的当前值，避免刚进页面就拉两次。
        viewModelScope.launch {
            prefs.homeSource.drop(1).collect { store.onSourceChanged(it) }
        }
    }

    fun refresh() = store.refresh()
}
