package com.mdblisthub.tv.player

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * The libVLC instance.
 *
 * Creating one loads several megabytes of native library and initialises the
 * decoder set, so there is exactly one per process and it outlives every
 * screen. Recreating it per playback is the single easiest way to make a TV
 * box stutter between episodes.
 */
class VlcEngine(context: Context) {

    val libVlc: LibVLC = LibVLC(context.applicationContext, buildOptions())

    fun release() = libVlc.release()

    private fun buildOptions(): ArrayList<String> = arrayListOf(
        // Streams here come off third-party CDNs and debrid mirrors over a
        // home Wi-Fi link. A second and a half of buffer is what keeps a
        // momentary dip from becoming a visible stall.
        "--network-caching=1500",
        "--file-caching=1500",
        "--live-caching=1500",

        // A CDN that drops the connection mid-file is routine; reconnecting is
        // far better than surfacing an error the user has to act on.
        "--http-reconnect",
        "--http-continuous",

        // Hardware decoding wherever the box offers it. Set boxes are weak
        // enough that software decoding a 1080p H.264 file is not a given, let
        // alone HEVC.
        "--avcodec-hw=any",

        // Never load a .srt sitting next to the file: everything played here
        // is a remote URL, and the guess only ever costs requests.
        "--no-sub-autodetect-file",

        // Keeps pitch sane if playback speed is ever exposed.
        "--audio-time-stretch",

        "--no-snapshot-preview",
        "-vv".takeIf { BuildFlags.VERBOSE } ?: "--quiet",
    )
}

internal object BuildFlags {
    /** libVLC logging is noisy enough to cost frames; off unless debugging. */
    const val VERBOSE = false
}
