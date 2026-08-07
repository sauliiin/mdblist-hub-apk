package com.mdblisthub.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a title, deciding on its own which link to use.
 *
 * The design rule this whole class exists to serve: **the user never picks a
 * source.** They press play, and a queue of candidates is walked until one
 * produces a frame. A dead mirror, an expired debrid token or a container the
 * decoder chokes on are all the same event here — move to the next one — and
 * none of them is worth a dialog.
 *
 * Success is defined as the player reaching `STATE_READY`, not as a command
 * being accepted — a 200-with-an-error-page opens a connection just as
 * readily as a real stream, but only a real one gets demuxed far enough to
 * report tracks and a duration.
 *
 * Every call here happens on the thread this was constructed on (the
 * ViewModel's main thread): ExoPlayer is single-threaded by contract, and
 * `viewModelScope` dispatches to Main, so the coroutines below stay on it.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val scope: CoroutineScope,
) : Player.Listener {

    /**
     * Per-candidate headers land here rather than on the MediaItem: some
     * debrid links 403 without the right user agent or referer, and the
     * factory is re-read on every `prepare`, so mutating it between attempts
     * is what makes the header follow the candidate being tried.
     */
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory),
            ),
        )
        // The whole reason this app moved off mpv. mpv buffers by byte count,
        // which on a mirror that trickles is a fixed and often far-too-small
        // amount of *time*; this budgets in seconds instead, so a slow source
        // simply takes longer to fill 30s than a fast one rather than
        // stuttering through playback.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 30_000,
                    /* maxBufferMs = */ 120_000,
                    // Start playing after 2.5s rather than the default 0.5s:
                    // starting early is what makes the first ten seconds of a
                    // stream the stutteriest part of it.
                    /* bufferForPlaybackMs = */ 2_500,
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                )
                // Keeps a minute behind the playhead so a small skip back
                // does not re-download what was just watched.
                .setBackBuffer(/* backBufferDurationMs = */ 60_000, /* retainBackBufferFromKeyframe = */ true)
                .build(),
        )
        .build()
        .apply { addListener(this@PlaybackController) }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Every candidate, already ranked. Walked twice before giving up. */
    private var queue: List<PlayableStream> = emptyList()
    private var queueIndex = -1
    private var passes = 0

    /** Resume point, applied once a source is actually ready. */
    private var resumePercent: Float? = null
    private var resumeApplied = false

    private var watchdog: Job? = null
    private var ticker: Job? = null
    private var pendingSubtitle: SubtitleOption? = null

    /**
     * ExoPlayer addresses tracks by (group, index); the pickers upstream
     * address them by a flat Int. These keep the translation, rebuilt every
     * time the track list changes.
     */
    private var audioOverrides: List<TrackSelectionOverride> = emptyList()
    private var subtitleOverrides: List<TrackSelectionOverride> = emptyList()

    /**
     * Starts the cascade. `resumeAtPercent` is applied to whichever source
     * ends up working, not to the first one tried.
     */
    fun play(candidates: List<PlayableStream>, resumeAtPercent: Float? = null) {
        stopInternal()

        queue = candidates.filter { it.playable }
        queueIndex = -1
        passes = 0
        resumePercent = resumeAtPercent?.takeIf { it > 1f && it < 95f }
        resumeApplied = false

        if (queue.isEmpty()) {
            _state.value = PlaybackState(
                phase = PlaybackPhase.FAILED,
                error = "Nenhum addon devolveu um link reproduzível para este título.",
            )
            return
        }

        _state.value = PlaybackState(
            phase = PlaybackPhase.RESOLVING,
            candidateCount = queue.size,
            externalSubtitle = pendingSubtitle,
        )
        advance()
    }

    /**
     * Moves to the next candidate.
     *
     * The queue is walked a second time before failing: a CDN that 403s on the
     * first pass has often minted a fresh token by the time it comes round
     * again, and one more attempt is far cheaper than telling someone to try
     * later.
     */
    private fun advance() {
        watchdog?.cancel()
        queueIndex++

        if (queueIndex >= queue.size) {
            passes++
            if (passes >= MAX_PASSES) {
                fail("Testei as ${queue.size} fontes disponíveis, duas vezes, e nenhuma abriu.")
                return
            }
            queueIndex = 0
        }

        val candidate = queue[queueIndex]
        val url = candidate.url ?: return advance()

        _state.update {
            it.copy(
                phase = PlaybackPhase.RESOLVING,
                attempt = queueIndex + 1,
                candidateCount = queue.size,
                error = null,
            )
        }

        // Replaced wholesale rather than merged: the factory is shared across
        // every attempt, so a header the previous mirror needed would
        // otherwise leak into this one's request.
        httpDataSourceFactory.setDefaultRequestProperties(candidate.headers)

        player.setMediaItem(buildMediaItem(url, pendingSubtitle))
        player.prepare()
        player.playWhenReady = true

        watchdog = scope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            // Still nothing ready: treat it as a dead source rather than
            // letting one unresponsive host hold the screen indefinitely.
            if (isActive) advance()
        }
    }

    /**
     * ExoPlayer side-loads subtitles as part of the media item, so changing
     * one mid-playback means rebuilding the item — see
     * [selectExternalSubtitle], which is what restores the position after.
     */
    private fun buildMediaItem(url: String, subtitle: SubtitleOption?): MediaItem {
        val builder = MediaItem.Builder().setUri(url)

        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                        .setMimeType(subtitleMimeType(subtitle.url))
                        .setLanguage(subtitle.lang)
                        .setLabel(subtitle.label)
                        // SELECT_FLAG_DEFAULT alone is a preference the track
                        // selector may ignore; FORCED makes it the one shown.
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    /** Addons label subtitles loosely; the extension is the reliable signal. */
    private fun subtitleMimeType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    // ------------------------------------------------------------ listener

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> onReady()
            Player.STATE_ENDED -> _state.update { it.copy(phase = PlaybackPhase.ENDED) }
            Player.STATE_BUFFERING -> _state.update {
                // A buffer stall mid-playback is worth showing; one during the
                // cascade is not, since the veil is already up.
                if (it.phase == PlaybackPhase.RESOLVING) it else it.copy(phase = PlaybackPhase.BUFFERING)
            }
            else -> Unit
        }
    }

    /**
     * Any playback error is a dead source, not a dead playback.
     *
     * This is the single biggest simplification over the mpv engine: mpv's
     * `end-file` fired identically whether a stream died or finished, so the
     * old code had to guess from how far the position was from the runtime.
     * ExoPlayer reports failures here and completion as `STATE_ENDED`, so the
     * two cases never need telling apart.
     */
    override fun onPlayerError(error: PlaybackException) {
        advance()
    }

    override fun onTracksChanged(tracks: Tracks) {
        val audio = collectTracks(tracks, C.TRACK_TYPE_AUDIO)
        val text = collectTracks(tracks, C.TRACK_TYPE_TEXT)

        audioOverrides = audio.map { it.second }
        subtitleOverrides = text.map { it.second }

        _state.update {
            it.copy(
                audioTracks = audio.map { entry -> entry.first },
                subtitleTracks = text.map { entry -> entry.first },
                currentAudioId = audio.indexOfFirst { entry -> entry.third },
                currentSubtitleId = text.indexOfFirst { entry -> entry.third },
            )
        }
    }

    /** Flattens ExoPlayer's group/index tracks into `(info, override, selected)`. */
    private fun collectTracks(
        tracks: Tracks,
        trackType: Int,
    ): List<Triple<TrackInfo, TrackSelectionOverride, Boolean>> {
        val out = mutableListOf<Triple<TrackInfo, TrackSelectionOverride, Boolean>>()

        for (group in tracks.groups) {
            if (group.type != trackType) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val index = out.size
                val label = format.label
                    ?: format.language?.let { lang -> "Faixa ${lang.uppercase()}" }
                    ?: "Faixa ${index + 1}"

                out += Triple(
                    TrackInfo(index, label),
                    TrackSelectionOverride(group.mediaTrackGroup, i),
                    group.isTrackSelected(i),
                )
            }
        }
        return out
    }

    private fun onReady() {
        watchdog?.cancel()
        watchdog = null

        if (!resumeApplied) {
            resumeApplied = true
            val duration = player.duration
            resumePercent?.takeIf { duration > 0 }?.let { percent ->
                player.seekTo((duration * percent / 100f).toLong())
            }
        }

        _state.update {
            it.copy(
                phase = if (player.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                durationMs = player.duration.coerceAtLeast(0),
                error = null,
            )
        }
        startTicker()
    }

    private fun fail(message: String) {
        watchdog?.cancel()
        ticker?.cancel()
        runCatching { player.stop() }
        _state.update { it.copy(phase = PlaybackPhase.FAILED, error = message) }
    }

    /**
     * ExoPlayer has no position callback, so the seek bar is polled. The tick
     * also keeps the play/pause phase honest, since pausing through the
     * engine (rather than through this class) is possible.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.coerceAtLeast(0),
                        phase = nextPollPhase(it.phase),
                    )
                }
            }
        }
    }

    private fun nextPollPhase(current: PlaybackPhase): PlaybackPhase = when (current) {
        PlaybackPhase.RESOLVING, PlaybackPhase.FAILED, PlaybackPhase.ENDED, PlaybackPhase.IDLE -> current
        else -> when {
            player.playbackState == Player.STATE_BUFFERING -> PlaybackPhase.BUFFERING
            player.isPlaying -> PlaybackPhase.PLAYING
            else -> PlaybackPhase.PAUSED
        }
    }

    // ----------------------------------------------------------- controls

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun pause() = runCatching {
        player.pause()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }.let { }

    fun resume() = runCatching {
        player.play()
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PLAYING) else it }
    }.let { }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val clamped = positionMs.coerceIn(0, duration)
        player.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /**
     * Walks [SCALE_CYCLE], wrapping — the "esticar"/aspect-ratio button.
     *
     * Only records the choice; the surface reads it and applies the matching
     * resize mode, because the mode belongs to the view, not the engine.
     */
    fun cycleScale() {
        val next = SCALE_CYCLE.getOrElse(SCALE_CYCLE.indexOf(_state.value.scaleType) + 1) { SCALE_CYCLE[0] }
        _state.update { it.copy(scaleType = next) }
    }

    fun selectAudioTrack(id: Int) {
        val override = audioOverrides.getOrNull(id) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
        _state.update { it.copy(currentAudioId = id) }
    }

    fun selectSubtitleTrack(id: Int) {
        val params = player.trackSelectionParameters.buildUpon()
        val override = subtitleOverrides.getOrNull(id)

        if (override == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(override)
        }

        player.trackSelectionParameters = params.build()
        _state.update { it.copy(currentSubtitleId = id) }
    }

    /**
     * Adds an addon's subtitle file.
     *
     * Unlike mpv's `sub-add`, ExoPlayer side-loads subtitles as part of the
     * media item, so switching one mid-playback means rebuilding that item
     * and re-preparing. The position is carried across by hand, which is what
     * keeps the change from looking like a restart.
     */
    fun selectExternalSubtitle(option: SubtitleOption?) {
        pendingSubtitle = option
        _state.update { it.copy(externalSubtitle = option) }

        if (option == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }

        val current = queue.getOrNull(queueIndex)?.url ?: return
        val resumeAt = player.currentPosition

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        // The watchdog would otherwise read this re-prepare as a source that
        // never became ready and skip to the next candidate.
        watchdog?.cancel()
        watchdog = null

        player.setMediaItem(buildMediaItem(current, option), resumeAt)
        player.prepare()
        player.playWhenReady = true
    }

    /** Where the scrobbler reads from: 0–100 of the running title. */
    fun progressPercent(): Float = _state.value.progress * 100f

    private fun stopInternal() {
        watchdog?.cancel()
        ticker?.cancel()
        watchdog = null
        ticker = null
        runCatching { player.stop() }
    }

    fun stop() {
        stopInternal()
        _state.value = PlaybackState()
    }

    /**
     * Tears the player down for good.
     *
     * Unlike the mpv and libVLC engines this replaced — both single, shared,
     * process-lifetime objects — an ExoPlayer is per-playback and cheap to
     * build, so it is released with the screen rather than kept warm.
     */
    fun release() {
        stopInternal()
        player.removeListener(this)
        player.release()
    }

    private companion object {
        /**
         * How long one source gets to become ready. Long enough for a cold
         * debrid link to warm up, short enough that walking nine dead mirrors
         * is still under a minute.
         */
        const val ATTEMPT_TIMEOUT_MS = 12_000L
        const val MAX_PASSES = 2
        const val TICK_MS = 500L
    }
}
