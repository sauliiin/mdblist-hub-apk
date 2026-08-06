package com.mdblisthub.tv.player

import android.net.Uri
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
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/**
 * Plays a title, deciding on its own which link to use.
 *
 * The design rule this whole class exists to serve: **the user never picks a
 * source.** They press play, and a queue of candidates is walked until one
 * produces a frame. A dead mirror, an expired debrid token or a container the
 * decoder chokes on are all the same event here — move to the next one — and
 * none of them is worth a dialog.
 *
 * Success is defined as video output, not as libVLC reporting "playing".
 * VLC says Playing as soon as it has opened a connection, which a 200-with-an-
 * error-page satisfies just as well as a real file; the first decoded frame
 * does not lie.
 */
class PlaybackController(
    engine: VlcEngine,
    private val scope: CoroutineScope,
) {
    private val libVlc = engine.libVlc
    val player: MediaPlayer = MediaPlayer(libVlc)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Every candidate, already ranked. Walked twice before giving up. */
    private var queue: List<PlayableStream> = emptyList()
    private var queueIndex = -1
    private var passes = 0

    /** Resume point, applied once the first frame arrives. */
    private var resumePercent: Float? = null
    private var resumeApplied = false

    private var watchdog: Job? = null
    private var ticker: Job? = null
    private var pendingSubtitle: SubtitleOption? = null

    init {
        player.setEventListener(::onEvent)
    }

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

        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=$NETWORK_CACHING_MS")

            // libVLC 3 exposes only these two of the headers an addon may ask
            // for, and they are the two that matter: several debrid mirrors
            // answer 403 without the user agent they issued the link to.
            candidate.headers["User-Agent"]?.let { addOption(":http-user-agent=$it") }
            candidate.headers["Referer"]?.let { addOption(":http-referrer=$it") }
        }

        player.media = media
        media.release()
        player.play()

        watchdog = scope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            // Still no picture: treat it as a dead source rather than letting
            // one unresponsive host hold the screen indefinitely.
            if (isActive) advance()
        }
    }

    private fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            // The frame that proves the source is real.
            MediaPlayer.Event.Vout -> if (event.voutCount > 0) onFirstFrame()

            MediaPlayer.Event.Playing -> _state.update {
                if (it.phase == PlaybackPhase.RESOLVING) it else it.copy(phase = PlaybackPhase.PLAYING)
            }

            MediaPlayer.Event.Paused -> _state.update {
                if (it.phase == PlaybackPhase.RESOLVING) it else it.copy(phase = PlaybackPhase.PAUSED)
            }

            MediaPlayer.Event.Buffering -> _state.update {
                if (it.phase == PlaybackPhase.RESOLVING || event.buffering >= 100f) it
                else it.copy(phase = PlaybackPhase.BUFFERING)
            }

            MediaPlayer.Event.EncounteredError -> advance()

            MediaPlayer.Event.EndReached -> {
                // A source that ends immediately never really started, so it
                // rejoins the cascade instead of being reported as finished.
                if (_state.value.phase == PlaybackPhase.RESOLVING) advance()
                else _state.update { it.copy(phase = PlaybackPhase.ENDED) }
            }

            MediaPlayer.Event.LengthChanged ->
                _state.update { it.copy(durationMs = event.lengthChanged) }

            MediaPlayer.Event.TimeChanged ->
                _state.update { it.copy(positionMs = event.timeChanged) }
        }
    }

    private fun onFirstFrame() {
        watchdog?.cancel()
        watchdog = null

        if (!resumeApplied) {
            resumeApplied = true
            resumePercent?.let { player.position = it / 100f }
        }
        pendingSubtitle?.let(::attachSubtitle)

        _state.update {
            it.copy(
                phase = PlaybackPhase.PLAYING,
                durationMs = player.length.coerceAtLeast(0),
                audioTracks = player.readTracks(audio = true),
                subtitleTracks = player.readTracks(audio = false),
                currentAudioId = player.audioTrack,
                currentSubtitleId = player.spuTrack,
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
     * libVLC reports time through events, but only while decoding — a paused
     * player goes silent. The tick keeps the seek bar honest and refreshes the
     * track lists, which arrive a beat after the first frame.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (!player.isReleased) {
                    _state.update {
                        it.copy(
                            positionMs = player.time.coerceAtLeast(0),
                            durationMs = player.length.coerceAtLeast(0),
                            currentAudioId = player.audioTrack,
                            currentSubtitleId = player.spuTrack,
                        )
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------- controls

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = runCatching { if (player.isPlaying) player.pause() }.let { }

    fun resume() = runCatching { if (!player.isPlaying) player.play() }.let { }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        player.time = positionMs.coerceIn(0, duration)
        _state.update { it.copy(positionMs = positionMs) }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /** Walks [SCALE_CYCLE], wrapping — the "esticar"/aspect-ratio button. */
    fun cycleScale() {
        val next = SCALE_CYCLE.getOrElse(SCALE_CYCLE.indexOf(_state.value.scaleType) + 1) { SCALE_CYCLE[0] }
        player.videoScale = next
        _state.update { it.copy(scaleType = next) }
    }

    fun selectAudioTrack(id: Int) {
        player.audioTrack = id
        _state.update { it.copy(currentAudioId = id) }
    }

    fun selectSubtitleTrack(id: Int) {
        player.spuTrack = id
        _state.update { it.copy(currentSubtitleId = id) }
    }

    /**
     * Adds an addon's subtitle file.
     *
     * The URL goes to the engine untouched: libVLC reads SRT and ASS directly,
     * so there is none of the download-decode-convert-to-WebVTT dance the
     * browser build needs.
     */
    fun selectExternalSubtitle(option: SubtitleOption?) {
        pendingSubtitle = option
        _state.update { it.copy(externalSubtitle = option) }

        if (option == null) {
            player.spuTrack = -1
            return
        }
        if (_state.value.canShowVideo) attachSubtitle(option)
    }

    private fun attachSubtitle(option: SubtitleOption) {
        runCatching {
            player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(option.url), true)
        }
        _state.update { it.copy(subtitleTracks = player.readTracks(audio = false)) }
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

    fun release() {
        stopInternal()
        runCatching {
            player.setEventListener(null)
            player.release()
        }
    }

    private companion object {
        /**
         * How long one source gets to produce a frame. Long enough for a
         * cold debrid link to warm up, short enough that walking nine dead
         * mirrors is still under a minute.
         */
        const val ATTEMPT_TIMEOUT_MS = 12_000L
        const val MAX_PASSES = 2
        const val NETWORK_CACHING_MS = 1500
        const val TICK_MS = 500L
    }
}

/** libVLC hands tracks back as a nullable array of descriptions. */
private fun MediaPlayer.readTracks(audio: Boolean): List<TrackInfo> {
    val descriptions = if (audio) audioTracks else spuTracks
    return descriptions.orEmpty().map { TrackInfo(it.id, it.name ?: "Faixa ${it.id}") }
}
