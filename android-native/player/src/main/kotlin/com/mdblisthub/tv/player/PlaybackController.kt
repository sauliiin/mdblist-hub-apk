package com.mdblisthub.tv.player

import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import dev.jdtech.mpv.MPVLib
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
 * Success is defined as the file actually loading (mpv's `file-loaded`
 * event), not as a command being accepted — a 200-with-an-error-page opens a
 * connection just as readily as a real stream, but only a real one gets its
 * headers and tracks parsed.
 */
class PlaybackController(
    engine: MpvEngine,
    private val scope: CoroutineScope,
) : MPVLib.EventObserver {

    val mpv: MPVLib = engine.mpv

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Every candidate, already ranked. Walked twice before giving up. */
    private var queue: List<PlayableStream> = emptyList()
    private var queueIndex = -1
    private var passes = 0

    /** Resume point, applied once the file has loaded. */
    private var resumePercent: Float? = null
    private var resumeApplied = false

    private var watchdog: Job? = null
    private var ticker: Job? = null
    private var pendingSubtitle: SubtitleOption? = null

    init {
        mpv.addObserver(this)
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

        // Explicitly reset to mpv's own default (an empty string) rather than
        // only setting when the candidate specifies one: the engine is one
        // shared mpv instance reused across every candidate, so a header the
        // previous mirror needed would otherwise leak into this one's request.
        mpv.setPropertyString("user-agent", candidate.headers["User-Agent"] ?: "")
        mpv.setPropertyString(
            "http-header-fields",
            candidate.headers["Referer"]?.let { "Referer: $it" } ?: "",
        )
        mpv.command(arrayOf("loadfile", url, "replace"))

        watchdog = scope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            // Still nothing loaded: treat it as a dead source rather than
            // letting one unresponsive host hold the screen indefinitely.
            if (isActive) advance()
        }
    }

    // mpv's own event thread calls these — never the UI thread. `_state` is a
    // StateFlow (safe to mutate from anywhere) and `scope.launch` is a safe
    // entry point from any thread, so nothing here needs to hop threads
    // itself; the libVLC listener this replaced ran under the same rule.
    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onFileLoaded()
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onEndFile()
            else -> Unit
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: String) = Unit

    private fun onFileLoaded() {
        watchdog?.cancel()
        watchdog = null

        if (!resumeApplied) {
            resumeApplied = true
            resumePercent?.let { mpv.setPropertyDouble("percent-pos", it.toDouble()) }
        }
        pendingSubtitle?.let(::attachSubtitle)

        _state.update {
            it.copy(
                phase = PlaybackPhase.PLAYING,
                durationMs = durationMs(),
                audioTracks = readTracks("audio"),
                subtitleTracks = readTracks("sub"),
                currentAudioId = mpv.getPropertyInt("aid") ?: -1,
                currentSubtitleId = mpv.getPropertyInt("sid") ?: -1,
                error = null,
            )
        }
        startTicker()
    }

    /**
     * A minimal JNI wrapper: unlike libVLC's distinct error/end-reached
     * events, mpv's `end-file` fires the same way whether the file finished
     * cleanly or died mid-stream, with no reason code surfaced here. Distance
     * from the known duration is what stands in for it — a source that drops
     * three minutes short of the runtime looks exactly like one that never
     * really started, so both rejoin the cascade instead of stopping
     * playback outright.
     */
    private fun onEndFile() {
        if (_state.value.phase == PlaybackPhase.RESOLVING) {
            advance()
            return
        }

        val duration = _state.value.durationMs
        val finishedCleanly = duration <= 0 || _state.value.positionMs >= duration - END_TOLERANCE_MS
        if (finishedCleanly) {
            _state.update { it.copy(phase = PlaybackPhase.ENDED) }
        } else {
            advance()
        }
    }

    private fun fail(message: String) {
        watchdog?.cancel()
        ticker?.cancel()
        runCatching { mpv.command(arrayOf("stop")) }
        _state.update { it.copy(phase = PlaybackPhase.FAILED, error = message) }
    }

    /**
     * mpv reports time through properties, which only change while decoding
     * — a paused player goes quiet. The tick keeps the seek bar honest, the
     * play/pause phase in sync with the engine, and refreshes the track
     * lists, which can grow after a subtitle is added mid-playback.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                _state.update {
                    it.copy(
                        positionMs = positionMs(),
                        durationMs = durationMs(),
                        currentAudioId = mpv.getPropertyInt("aid") ?: it.currentAudioId,
                        currentSubtitleId = mpv.getPropertyInt("sid") ?: it.currentSubtitleId,
                        phase = nextPollPhase(it.phase),
                    )
                }
            }
        }
    }

    private fun nextPollPhase(current: PlaybackPhase): PlaybackPhase = when (current) {
        PlaybackPhase.RESOLVING, PlaybackPhase.FAILED, PlaybackPhase.ENDED, PlaybackPhase.IDLE -> current
        else -> when {
            mpv.getPropertyBoolean("paused-for-cache") == true -> PlaybackPhase.BUFFERING
            mpv.getPropertyBoolean("pause") == true -> PlaybackPhase.PAUSED
            else -> PlaybackPhase.PLAYING
        }
    }

    private fun positionMs(): Long = ((mpv.getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong().coerceAtLeast(0)
    private fun durationMs(): Long = ((mpv.getPropertyDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    /** mpv hands tracks back as an indexed list of sub-properties, not a struct. */
    private fun readTracks(type: String): List<TrackInfo> {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        return (0 until count).mapNotNull { i ->
            if (mpv.getPropertyString("track-list/$i/type") != type) return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val label = mpv.getPropertyString("track-list/$i/title")
                ?: mpv.getPropertyString("track-list/$i/lang")?.let { "Faixa $it" }
                ?: "Faixa $id"
            TrackInfo(id, label)
        }
    }

    // ----------------------------------------------------------- controls

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun pause() = runCatching {
        mpv.setPropertyBoolean("pause", true)
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }.let { }

    fun resume() = runCatching {
        mpv.setPropertyBoolean("pause", false)
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PLAYING) else it }
    }.let { }

    fun seekTo(positionMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0) return
        val clamped = positionMs.coerceIn(0, duration)
        mpv.setPropertyDouble("time-pos", clamped / 1000.0)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /** Walks [SCALE_CYCLE], wrapping — the "esticar"/aspect-ratio button. */
    fun cycleScale() {
        val next = SCALE_CYCLE.getOrElse(SCALE_CYCLE.indexOf(_state.value.scaleType) + 1) { SCALE_CYCLE[0] }
        applyScale(next)
        _state.update { it.copy(scaleType = next) }
    }

    private fun applyScale(type: MpvScaleType) {
        mpv.setPropertyString(
            "video-aspect-override",
            when (type) {
                MpvScaleType.RATIO_16_9 -> "16:9"
                MpvScaleType.RATIO_4_3 -> "4:3"
                else -> "no"
            },
        )
        mpv.setPropertyDouble("panscan", if (type == MpvScaleType.FILL) 1.0 else 0.0)
        mpv.setPropertyBoolean("video-unscaled", type == MpvScaleType.ORIGINAL)
    }

    fun selectAudioTrack(id: Int) {
        mpv.setPropertyInt("aid", id)
        _state.update { it.copy(currentAudioId = id) }
    }

    fun selectSubtitleTrack(id: Int) {
        if (id < 0) mpv.setPropertyString("sid", "no") else mpv.setPropertyInt("sid", id)
        _state.update { it.copy(currentSubtitleId = id) }
    }

    /**
     * Adds an addon's subtitle file.
     *
     * The URL goes to the engine untouched: mpv reads SRT and ASS directly,
     * so there is none of the download-decode-convert-to-WebVTT dance the
     * browser build needs.
     */
    fun selectExternalSubtitle(option: SubtitleOption?) {
        pendingSubtitle = option
        _state.update { it.copy(externalSubtitle = option) }

        if (option == null) {
            mpv.setPropertyString("sid", "no")
            return
        }
        if (_state.value.canShowVideo) attachSubtitle(option)
    }

    private fun attachSubtitle(option: SubtitleOption) {
        runCatching { mpv.command(arrayOf("sub-add", option.url, "select")) }
        _state.update { it.copy(subtitleTracks = readTracks("sub")) }
    }

    /** Where the scrobbler reads from: 0–100 of the running title. */
    fun progressPercent(): Float = _state.value.progress * 100f

    private fun stopInternal() {
        watchdog?.cancel()
        ticker?.cancel()
        watchdog = null
        ticker = null
        runCatching { mpv.command(arrayOf("stop")) }
    }

    fun stop() {
        stopInternal()
        _state.value = PlaybackState()
    }

    /**
     * Releases this playback's hold on the shared engine. mpv itself is not
     * torn down here — [MpvEngine] owns it for the process's lifetime, the
     * same way the libVLC engine this replaced was never rebuilt between
     * episodes.
     */
    fun release() {
        stopInternal()
        mpv.removeObserver(this)
    }

    private companion object {
        /**
         * How long one source gets to produce a frame. Long enough for a
         * cold debrid link to warm up, short enough that walking nine dead
         * mirrors is still under a minute.
         */
        const val ATTEMPT_TIMEOUT_MS = 12_000L
        const val MAX_PASSES = 2
        const val TICK_MS = 500L

        /** How close to the known duration counts as "really" ended. */
        const val END_TOLERANCE_MS = 4_000L
    }
}
