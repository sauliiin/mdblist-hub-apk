package com.mdblisthub.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.ResumePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val graph: DataGraph) : ViewModel() {

    /**
     * Straight out of Room. The home paints on the first frame from whatever
     * the last sync left behind; the refresh below writes over it whenever it
     * finishes, and nothing on screen ever waits for the network.
     */
    val lists: StateFlow<List<MediaList>> = graph.lists.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resumePoints: StateFlow<List<ResumePoint>> = graph.playback.resumePoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Drives the fanart and the panel above the rows. */
    private val _focused = MutableStateFlow<MediaItem?>(null)
    val focused: StateFlow<MediaItem?> = _focused.asStateFlow()

    init {
        viewModelScope.launch {
            graph.lists.refreshLists()
            graph.playback.refreshResumePoints()
            // New titles usually arrived with that sync; let the hydration
            // worker know there is something to chew on.
            graph.scheduler.hydrateSoon()
        }
    }

    fun onFocused(item: MediaItem) {
        _focused.value = item
        // Warming the detail on focus is what makes opening a title feel
        // instant: by the time the user presses OK, it is already in Room.
        graph.prefetcher.prefetch(item.type, item.tmdbId)
    }

    fun ensureItems(listId: Long) {
        viewModelScope.launch { graph.lists.refreshItems(listId) }
    }

    fun loadMore(listId: Long) {
        viewModelScope.launch { graph.lists.loadMore(listId) }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            graph.scheduler.onSignedOut()
            graph.auth.signOut()
            onDone()
        }
    }
}
