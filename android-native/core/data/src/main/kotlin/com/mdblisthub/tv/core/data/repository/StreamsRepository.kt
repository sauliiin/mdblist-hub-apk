package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.mapper.rankForPlayback
import com.mdblisthub.tv.core.data.mapper.rankSubtitles
import com.mdblisthub.tv.core.data.mapper.toOption
import com.mdblisthub.tv.core.data.mapper.toPlayable
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.network.StremioApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

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
) {

    /**
     * Ranked, deduplicated, playable-only. An addon that fails contributes
     * nothing rather than failing the call — with a dozen installed, one being
     * down is the normal case, not an error worth surfacing.
     */
    suspend fun candidates(type: MediaType, id: String): List<PlayableStream> = coroutineScope {
        val providers = addons.addons().filter { it.serves("stream", type, id) }
        if (providers.isEmpty()) return@coroutineScope emptyList()

        val encoded = URLEncoder.encode(id, "UTF-8")

        providers
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
    }

    /**
     * The same fan-out for subtitles.
     *
     * mpv reads SRT and ASS straight off a URL, so unlike the browser build
     * there is no download-and-convert step — the address goes to the engine
     * as it arrived.
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
}
