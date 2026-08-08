package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.mapper.SubtitleFileParser
import com.mdblisthub.tv.core.data.mapper.rankForPlayback
import com.mdblisthub.tv.core.data.mapper.rankSubtitles
import com.mdblisthub.tv.core.data.mapper.toOption
import com.mdblisthub.tv.core.data.mapper.toPlayable
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.SubtitleTrack
import com.mdblisthub.tv.core.network.StremioApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Where a title's playable links come from.
 *
 * The result is deliberately not a menu. Nothing in this app ever shows the
 * user a list of sources to choose between: `candidates` returns them already
 * ranked, and the player walks that order until one plays. Which mirror or
 * release ends up on screen is an implementation detail of getting the film
 * playing, not a question worth interrupting someone with.
 */
class StreamsRepository(
    private val api: StremioApi,
    private val addons: AddonsRepository,
    addonClient: OkHttpClient,
) {

    /**
     * Tuned for a yes/no signal, not for the download that follows: the
     * shared addon client's own timeouts (6s connect / 8s read) are sized for
     * fetching a JSON manifest, not for finding out in a hurry whether a
     * video mirror is even alive.
     */
    private val probeClient = addonClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * A subtitle is a whole file, not a yes/no answer, so it gets room to
     * finish where [probeClient] is tuned to give up fast.
     */
    private val subtitleClient = addonClient.newBuilder()
        .callTimeout(SUBTITLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Ranked, deduplicated, playable-only — delivered as they are verified.
     *
     * A flow rather than a list, and that is the whole point. The previous
     * version probed every mirror in parallel and then waited for the *slowest*
     * one before returning anything, so a link that answered in 200ms still
     * sat behind three and a half seconds of dead hosts timing out, and only
     * then did ExoPlayer get to start. Here the probes still all run at once,
     * but each result is handed over the moment it lands.
     *
     * Awaiting them in rank order rather than completion order is deliberate:
     * emitting whoever answers first would quietly hand the film to a 480p
     * mirror because it happened to be closer than the 1080p one. So the top
     * candidate is waited for — at most one probe timeout — and everything
     * behind it has already been probed concurrently by then, so the rest
     * arrive with no further delay.
     *
     * An addon that fails contributes nothing rather than failing the call:
     * with a dozen installed, one being down is the normal case.
     */
    fun candidates(type: MediaType, id: String): Flow<PlayableStream> = channelFlow {
        val providers = addons.addons().filter { it.serves("stream", type, id) }
        if (providers.isEmpty()) return@channelFlow

        val encoded = URLEncoder.encode(id, "UTF-8")

        val ranked = providers
            .map { addon ->
                async {
                    runCatching {
                        api.streams("${addon.base}/stream/${type.stremio}/$encoded.json")
                            .streams
                            .mapIndexed { index, dto -> dto.toPlayable(addon, index) }
                    }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .rankForPlayback()

        if (ranked.isEmpty()) return@channelFlow

        // The best-ranked candidate goes out unprobed, immediately. Actually
        // opening it in the player is a stricter test than a range request
        // anyway, so making it wait behind a probe only adds that probe's
        // timeout to the one case that matters most — and debrid endpoints in
        // particular routinely refuse a two-byte request, then stream fine.
        send(ranked.first())

        val rest = ranked.drop(1)
        if (rest.isEmpty()) return@channelFlow

        // Bounded so a title with forty mirrors does not open forty sockets at
        // once on a set-top box; six is enough to keep the queue moving.
        val gate = Semaphore(PROBE_CONCURRENCY)
        val probes = rest.map { stream ->
            async { stream to gate.withPermit { isReachable(stream) } }
        }

        // A mirror that flunks the probe is deprioritized, never dropped, for
        // the same reason the first one skips it: a failed probe is weak
        // evidence. They stay at the back as fallback material.
        val unverified = mutableListOf<PlayableStream>()
        for (probe in probes) {
            val (stream, reachable) = probe.await()
            if (reachable) send(stream) else unverified += stream
        }
        unverified.forEach { send(it) }
    }

    private suspend fun isReachable(stream: PlayableStream): Boolean {
        val url = stream.url ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-1")
                    .apply { stream.headers.forEach { (key, value) -> header(key, value) } }
                    .build()
                probeClient.newCall(request).execute().use { it.code < 400 }
            }.getOrDefault(false)
        }
    }

    /**
     * Downloads and parses one subtitle file into cues the player owns.
     *
     * The URL used to go straight to Media3 as part of the video's MediaItem.
     * It no longer does: holding the cues ourselves is what makes adjusting
     * the synchronization offset instant instead of a full re-prepare of the
     * video. See [SubtitleFileParser].
     */
    suspend fun subtitleTrack(option: SubtitleOption): SubtitleTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(option.url).build()
            subtitleClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                SubtitleFileParser.parse(response.body.bytes(), option.encoding)
            }
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    /**
     * The same fan-out for subtitles.
     *
     * This lists what is on offer; [subtitleTrack] is what fetches one.
     */
    suspend fun subtitles(type: MediaType, id: String): List<SubtitleOption> = coroutineScope {
        val providers = addons.addons().filter { it.serves("subtitles", type, id) }
        if (providers.isEmpty()) return@coroutineScope emptyList()

        val encoded = URLEncoder.encode(id, "UTF-8")

        providers
            .map { addon ->
                async {
                    runCatching {
                        api.subtitles("${addon.base}/subtitles/${type.stremio}/$encoded.json")
                            .subtitles
                            .mapIndexed { index, dto -> dto.toOption(addon, index) }
                    }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .rankSubtitles()
    }

    /** How many installed addons even claim to serve streams for this id. */
    suspend fun providerCount(type: MediaType, id: String): Int =
        addons.addons().count { it.serves("stream", type, id) }

    private companion object {
        /**
         * Long enough for a real mirror to answer two bytes, short enough
         * that a dozen of these in parallel still lands well under what one
         * dead source used to cost alone in the old sequential-only cascade.
         */
        const val PROBE_TIMEOUT_MS = 3_500L
        const val SUBTITLE_TIMEOUT_MS = 15_000L
        const val PROBE_CONCURRENCY = 6
    }
}
