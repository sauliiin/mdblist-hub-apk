package com.mdblisthub.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.player.PlaybackController
import com.mdblisthub.tv.player.PlaybackPhase
import com.mdblisthub.tv.player.VlcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val title: String = "",
    val episodeLabel: String? = null,
    val backdropUrl: String? = null,
    /** Set only while the addons are being asked, before the cascade starts. */
    val searching: Boolean = true,
    val subtitles: List<SubtitleOption> = emptyList(),
    val noAddons: Boolean = false,
    val missingImdbId: Boolean = false,
)

/**
 * Drives one playback.
 *
 * The order of operations is the whole user-visible design: ask the addons,
 * rank what comes back, hand the *entire* ranked list to the controller and
 * let it find one that works. At no point is there a list for anyone to look
 * at — pressing play is the last decision the user makes.
 */
class PlayerViewModel(
    private val graph: DataGraph,
    engine: VlcEngine,
    private val type: MediaType,
    private val tmdbId: Int,
    private val season: Int?,
    private val episode: Int?,
) : ViewModel() {

    val controller = PlaybackController(engine, viewModelScope)

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private var target: ScrobbleTarget? = null
    private var lastReportedProgress = 0f

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { reportPlaybackToMdblist() }
    }

    private suspend fun start() {
        graph.media.ensureDetail(type, tmdbId)
        val detail = graph.media.observeDetail(type, tmdbId).first()

        _ui.update {
            it.copy(
                title = detail?.title.orEmpty(),
                backdropUrl = detail?.backdropUrl,
                episodeLabel = if (type == MediaType.SHOW && season != null && episode != null) {
                    "T${season}E$episode"
                } else {
                    detail?.year?.toString()
                },
            )
        }

        val imdbId = detail?.imdbId
        if (imdbId.isNullOrBlank()) {
            // Addons are indexed by IMDb id; without one there is nothing to
            // ask, and no cascade to run.
            _ui.update { it.copy(searching = false, missingImdbId = true) }
            return
        }

        val scrobbleTarget = ScrobbleTarget(type, tmdbId, imdbId, season, episode)
        target = scrobbleTarget
        val stremioId = scrobbleTarget.stremioId() ?: imdbId

        if (graph.addons.addons().isEmpty()) {
            _ui.update { it.copy(searching = false, noAddons = true) }
            return
        }

        val candidates = graph.streams.candidates(type, stremioId)
        val resumeAt = graph.playback.resumeFor(scrobbleTarget)

        _ui.update { it.copy(searching = false) }
        controller.play(candidates, resumeAt)

        // Subtitles are fetched after playback has been handed off: they take
        // as long as the streams did, and nothing should wait on them.
        viewModelScope.launch {
            val options = graph.streams.subtitles(type, stremioId)
            _ui.update { it.copy(subtitles = options) }
        }
    }

    /**
     * mdblist owns the playback position, so every transition is reported.
     * Past 80% it marks the title watched on its own.
     */
    private suspend fun reportPlaybackToMdblist() {
        controller.state
            .distinctUntilChangedBy { it.phase }
            .collect { state ->
                val current = target ?: return@collect
                lastReportedProgress = state.progress * 100f

                when (state.phase) {
                    PlaybackPhase.PLAYING -> graph.playback.start(current, lastReportedProgress)
                    PlaybackPhase.PAUSED -> graph.playback.pause(current, lastReportedProgress)
                    PlaybackPhase.ENDED -> graph.playback.stop(current, lastReportedProgress)
                    else -> Unit
                }
            }
    }

    fun selectSubtitle(option: SubtitleOption?) = controller.selectExternalSubtitle(option)

    override fun onCleared() {
        val current = target
        val progress = controller.progressPercent().takeIf { it > 0f } ?: lastReportedProgress

        // Fire-and-forget on the application scope: the ViewModel's own scope
        // is already cancelled by the time this runs, and losing the stop is
        // losing the resume point.
        if (current != null && progress > 0f) {
            graph.scope.launch { graph.playback.stop(current, progress) }
        }
        controller.release()
        super.onCleared()
    }
}
