package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toResumeEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.model.ScrobbleTarget
import com.mdblisthub.tv.core.network.MdblistApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Playback position, kept by mdblist rather than by this app.
 *
 * `pause` and `stop` store the point, `start` replaces it, and
 * `/sync/playback` hands the paused ones back — which is what makes a film
 * resumable on the phone after being left half-watched on the television.
 * Past 80% mdblist marks the title watched on its own.
 */
class PlaybackRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val dao = database.playbackDao()

    /**
     * The "continue watching" row, mirrored into Room so it paints on a cold
     * start before the network answers. A title all but finished is not
     * something to offer resuming.
     */
    val resumePoints: Flow<List<ResumePoint>> =
        dao.observeResumePoints().map { rows ->
            rows.map { it.toDomain() }.filter { it.progress > 1f && it.progress < 95f }
        }

    suspend fun refreshResumePoints(): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val now = System.currentTimeMillis()
        val sessions = api.playback(key).mapNotNull { it.toResumeEntity(now) }
        dao.replaceResumePoints(sessions)
    }

    suspend fun resumeFor(target: ScrobbleTarget): Float? =
        resumePoints.first().firstOrNull { it.matches(target) }?.progress

    suspend fun start(target: ScrobbleTarget, progress: Float) = send("start", target, progress)
    suspend fun pause(target: ScrobbleTarget, progress: Float) = send("pause", target, progress)
    suspend fun stop(target: ScrobbleTarget, progress: Float) = send("stop", target, progress)

    /** Drops a stored session — "remover de continuar assistindo". */
    suspend fun clear(target: ScrobbleTarget): Result<Unit> {
        val result = send("clear", target, 0f)
        if (result.isSuccess) {
            val key = "${target.type.mdblist}:${target.tmdbId ?: target.imdbId}:" +
                "${target.season ?: 0}:${target.episode ?: 0}"
            dao.deleteResumePoint(key)
        }
        return result
    }

    /**
     * The body goes out form-encoded with the target in bracket notation
     * (`movie[ids][imdb]`), which is the shape mdblist's schema documents.
     */
    private suspend fun send(
        action: String,
        target: ScrobbleTarget,
        progress: Float,
    ): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank() || (target.imdbId == null && target.tmdbId == null)) return@runCatching

        val root = if (target.type == MediaType.SHOW) "show" else "movie"
        val fields = buildMap {
            put("progress", String.format(Locale.US, "%.2f", progress))
            target.imdbId?.let { put("$root[ids][imdb]", it) }
            target.tmdbId?.let { put("$root[ids][tmdb]", it.toString()) }
            if (root == "show" && target.season != null && target.episode != null) {
                put("show[season]", target.season.toString())
                put("show[episode]", target.episode.toString())
            }
        }

        val response = api.scrobble(action, key, fields)
        check(response.isSuccessful) { "scrobble/$action respondeu ${response.code()}" }
    }
}

private fun ResumePoint.matches(target: ScrobbleTarget): Boolean {
    val sameTitle = if (target.imdbId != null) imdbId == target.imdbId else tmdbId == target.tmdbId
    if (!sameTitle) return false
    if (target.type != MediaType.SHOW) return true
    return season == target.season && episode == target.episode
}
