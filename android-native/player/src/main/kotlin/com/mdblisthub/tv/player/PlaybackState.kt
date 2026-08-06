package com.mdblisthub.tv.player

import com.mdblisthub.tv.core.model.SubtitleOption
import org.videolan.libvlc.MediaPlayer

/**
 * The cycle the "esticar" button walks, in the order it walks it.
 *
 * `SURFACE_BEST_FIT` is libVLC's own default — letterboxed, nothing cropped —
 * so it opens every playback and is the state to fall back to. `SURFACE_FILL`
 * is the one people actually reach for on a phone showing a widescreen
 * release: it crops top and bottom rather than pillarboxing to nothing.
 */
val SCALE_CYCLE = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT,
    MediaPlayer.ScaleType.SURFACE_FILL,
    MediaPlayer.ScaleType.SURFACE_16_9,
    MediaPlayer.ScaleType.SURFACE_4_3,
    MediaPlayer.ScaleType.SURFACE_ORIGINAL,
)

/** A label worth showing for a couple of seconds after the button is pressed. */
fun MediaPlayer.ScaleType.label(): String = when (this) {
    MediaPlayer.ScaleType.SURFACE_BEST_FIT -> "Ajustar à tela"
    MediaPlayer.ScaleType.SURFACE_FILL -> "Preencher"
    MediaPlayer.ScaleType.SURFACE_16_9 -> "16:9"
    MediaPlayer.ScaleType.SURFACE_4_3 -> "4:3"
    MediaPlayer.ScaleType.SURFACE_ORIGINAL -> "Tamanho original"
    else -> name
}

/** Where playback is, as one value the UI can render without branching twice. */
enum class PlaybackPhase {
    IDLE,

    /**
     * A source is being tried. This is the phase the source cascade lives in,
     * and the one the screen covers with artwork — the user is told the film
     * is starting, not which of nine mirrors is currently being probed.
     */
    RESOLVING,

    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,

    /** Every candidate was tried, twice, and none produced a frame. */
    FAILED,
}

/** One selectable audio or subtitle track, as libVLC reports it. */
data class TrackInfo(val id: Int, val label: String)

data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    /** 1-based position within a single pass over the candidates. */
    val attempt: Int = 0,
    val candidateCount: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audioTracks: List<TrackInfo> = emptyList(),
    val subtitleTracks: List<TrackInfo> = emptyList(),
    val currentAudioId: Int = -1,
    val currentSubtitleId: Int = -1,
    val externalSubtitle: SubtitleOption? = null,
    val scaleType: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT,
    val error: String? = null,
) {
    val isPlaying: Boolean get() = phase == PlaybackPhase.PLAYING
    val canShowVideo: Boolean
        get() = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.PAUSED ||
            phase == PlaybackPhase.BUFFERING

    /** 0f–1f, for the seek bar. */
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}
