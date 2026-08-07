package com.mdblisthub.tv.player

import android.content.Context
import dev.jdtech.mpv.MPVLib

/**
 * The mpv instance.
 *
 * Creating one loads several megabytes of native library and spins up mpv's
 * own event thread, so — same reasoning libVLC needed here before it — there
 * is exactly one per process, built on first use and kept for good.
 * `PlaybackController` borrows it for each playback rather than owning one
 * outright; recreating it between episodes is the easiest way to make a
 * set-top box stutter.
 */
class MpvEngine(context: Context) {

    val mpv: MPVLib = MPVLib.create(context.applicationContext)
        ?: error("mpv failed to initialize")

    init {
        // `wid` is how this wrapper's native side points mpv at the Surface
        // `MpvVideoSurface` attaches — `gpu`/`android` is the context that
        // renders onto a raw Surface handed over that way.
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")

        // Hardware decode, copied into a normal buffer rather than rendered
        // zero-copy: zero-copy MediaCodec output needs a SurfaceTexture-
        // backed GL context, which this wrapper's plain-Surface `wid` path
        // does not set up.
        mpv.setOptionString("hwdec", "mediacodec-copy")
        mpv.setOptionString("ao", "audiotrack")

        // A CDN that drops mid-file is routine on debrid mirrors; this is
        // ffmpeg's own reconnect logic, the mpv equivalent of what
        // `--http-reconnect`/`--http-continuous` did for the libVLC engine.
        mpv.setOptionString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")

        // Never guess at a local subtitle file: everything played here is a
        // remote URL, and the guess only ever costs requests.
        mpv.setOptionString("sub-auto", "no")

        // Bigger and yellow — legible from a couch over any backdrop, and
        // the classic choice for exactly that reason. `sub-ass-override=force`
        // is what makes this apply even to a subtitle that ships its own
        // ASS styling, not just a plain SRT.
        mpv.setOptionString("sub-font-size", "70")
        mpv.setOptionString("sub-color", "#FFFF00")
        mpv.setOptionString("sub-ass-override", "force")

        // Keeps pitch sane if playback speed is ever exposed.
        mpv.setOptionString("audio-pitch-correction", "yes")

        if (!BuildFlags.VERBOSE) mpv.setOptionString("msg-level", "all=warn")

        mpv.init()
    }

    fun release() = mpv.destroy()
}

internal object BuildFlags {
    /** mpv logging is noisy enough to cost frames; off unless debugging. */
    const val VERBOSE = false
}
