package com.mdblisthub.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.RecommendationRow
import com.mdblisthub.tv.core.model.ResumePoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(private val graph: DataGraph) : ViewModel() {

    /**
     * Rows are lazy and routinely leave composition while the user scrolls.
     * Keeping one StateFlow per list preserves its last Room emission, so a
     * row returning from above is full-sized immediately instead of briefly
     * reappearing as an empty, zero-height item during focus search.
     */
    private val itemFlows = mutableMapOf<Long, StateFlow<List<MediaItem>>>()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    /**
     * Straight out of Room. The home paints on the first frame from whatever
     * the last sync left behind; the refresh below writes over it whenever it
     * finishes, and nothing on screen ever waits for the network.
     */
    val lists: StateFlow<List<MediaList>> = kotlinx.coroutines.flow.combine(graph.lists.observeLists(), _isEditMode) { lists, editMode ->
        if (editMode) lists else lists.filter { !it.hidden }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resumePoints: StateFlow<List<ResumePoint>> = graph.playback.resumePoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleEditMode() {
        _isEditMode.value = !_isEditMode.value
    }

    fun toggleListVisibility(list: MediaList) {
        viewModelScope.launch {
            graph.lists.toggleVisibility(list.id, !list.hidden)
        }
    }

    fun itemsFor(listId: Long): StateFlow<List<MediaItem>> =
        itemFlows.getOrPut(listId) {
            graph.lists.observeItems(listId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    /** Drives the panel above the rows. */
    private val _focused = MutableStateFlow<MediaItem?>(null)
    val focused: StateFlow<MediaItem?> = _focused.asStateFlow()

    /**
     * Drives the fanart specifically — separate from [focused] because a
     * list card only ever carries a poster, never a backdrop (mdblist's list
     * endpoint doesn't return one), so the raw item's own `backdropUrl` is
     * always null off every row except "Continuar assistindo". Falling back
     * to the poster there means a portrait image stretched to cover a
     * full-bleed landscape panel — soft to the point of looking broken.
     *
     * This instead watches the *cached detail* for whatever is focused right
     * now, which [MetadataPrefetcher] is already warming on the same focus
     * event for the "open feels instant" reason. The poster fallback still
     * covers the gap before that detail lands; once it does, the fanart
     * upgrades to the real backdrop in place.
     *
     * Debounced, and [focused] deliberately is not: the panel's title and
     * year are a text swap and should track the remote exactly, but each
     * distinct URL here is a full-screen `w1280` decode. Sweeping a row used
     * to queue one per card passed over, which is the single heaviest thing
     * the home screen did.
     */
    val focusedBackdropUrl: StateFlow<String?> = _focused
        .debounce { if (it == null) 0L else FANART_SETTLE_MS }
        .flatMapLatest { item ->
            if (item == null) {
                flowOf(null)
            } else {
                graph.media.observeDetail(item.type, item.tmdbId)
                    .map { detail -> detail?.backdropUrl ?: item.backdropUrl ?: item.posterUrl }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val focusedDetail: StateFlow<com.mdblisthub.tv.core.model.MediaDetail?> = _focused
        .debounce { if (it == null) 0L else FANART_SETTLE_MS }
        .flatMapLatest { item ->
            if (item == null) {
                flowOf(null)
            } else {
                graph.media.observeDetail(item.type, item.tmdbId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Porque você assistiu" — built once per visit, not persisted: unlike
     * the mdblist rows above, TMDB's recommendations have nothing worth
     * caching in Room for, and the five seeds are cheap to re-derive from
     * whatever "Last Watched" looks like right now.
     */
    private val _becauseYouWatched = MutableStateFlow<List<RecommendationRow>>(emptyList())
    val becauseYouWatched: StateFlow<List<RecommendationRow>> = _becauseYouWatched.asStateFlow()

    init {
        viewModelScope.launch {
            graph.lists.refreshLists()
            graph.playback.refreshResumePoints()
            // New titles usually arrived with that sync; let the hydration
            // worker know there is something to chew on.
            graph.scheduler.hydrateSoon()
        }
        viewModelScope.launch {
            _becauseYouWatched.value = graph.recommendations.becauseYouWatched()
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

    private companion object {
        /**
         * Matched to the prefetcher's own settle delay: the fanart wants the
         * detail row that focus-warming is fetching, so waiting the same
         * beat means the backdrop usually arrives instead of the poster
         * fallback flashing first.
         */
        const val FANART_SETTLE_MS = 350L
    }
}
