package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.buildDetailEntity
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.MdblistApi
import com.mdblisthub.tv.core.network.OmdbApi
import com.mdblisthub.tv.core.network.TmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One title, assembled from three APIs and kept in Room.
 *
 * `observeDetail` never touches the network — it emits the cached row, or null
 * when there is none. `ensureDetail` is what fills it, and the workers call
 * the same path ahead of time so that by the time someone opens a title the
 * answer is usually already sitting in the database.
 */
class MediaRepository(
    private val tmdbApi: TmdbApi,
    private val mdblistApi: MdblistApi,
    private val omdbApi: OmdbApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val mediaDao = database.mediaDao()

    fun observeDetail(type: MediaType, tmdbId: Int): Flow<MediaDetail?> =
        mediaDao.observeDetail(tmdbId, type.mdblist).map { it?.toDomain() }

    fun observeEpisodes(showTmdbId: Int, seasonNumber: Int): Flow<List<Episode>> =
        mediaDao.observeEpisodes(showTmdbId, seasonNumber).map { rows -> rows.map { it.toDomain() } }

    suspend fun ensureDetail(
        type: MediaType,
        tmdbId: Int,
        force: Boolean = false,
    ): Result<Unit> = runCatching {
        val cached = mediaDao.detail(tmdbId, type.mdblist)
        if (!force && cached != null && !CachePolicy.isStale(cached.fetchedAt, CachePolicy.DETAIL_MS)) {
            return@runCatching
        }
        hydrate(type, tmdbId)
    }

    /**
     * The expensive path, and the one the metadata worker runs in bulk.
     *
     * TMDB goes first because it resolves the IMDb id OMDb is keyed by;
     * mdblist and OMDb then run together. Both are allowed to fail — a title
     * with no aggregated ratings still has a detail screen worth showing.
     */
    suspend fun hydrate(type: MediaType, tmdbId: Int) = coroutineScope {
        val tmdb = tmdbApi.detail(
            type = type.tmdb,
            tmdbId = tmdbId,
            apiKey = ApiConfig.TMDB_KEY,
            language = ApiConfig.LANGUAGE,
            append = TmdbApi.DETAIL_APPEND,
            imageLanguage = TmdbApi.IMAGE_LANGUAGES,
        )

        val imdbId = tmdb.externalIds?.imdbId
        val apiKey = session.currentKey()

        val info = async {
            if (apiKey.isBlank()) null
            else runCatching { mdblistApi.info(type.mdblist, tmdbId, apiKey, "review") }.getOrNull()
        }
        val omdb = async {
            if (imdbId.isNullOrBlank()) null
            else runCatching { omdbApi.byImdb(ApiConfig.OMDB_KEY, imdbId, "full") }.getOrNull()
        }

        val entity = buildDetailEntity(
            type = type,
            tmdbId = tmdbId,
            tmdb = tmdb,
            info = info.await(),
            omdb = omdb.await(),
            now = System.currentTimeMillis(),
        )
        mediaDao.upsertDetail(entity)
    }

    suspend fun ensureEpisodes(
        showTmdbId: Int,
        seasonNumber: Int,
        force: Boolean = false,
    ): Result<Unit> = runCatching {
        val fetchedAt = mediaDao.episodesFetchedAt(showTmdbId, seasonNumber)
        if (!force && !CachePolicy.isStale(fetchedAt, CachePolicy.EPISODES_MS)) return@runCatching

        val now = System.currentTimeMillis()
        val season = tmdbApi.season(showTmdbId, seasonNumber, ApiConfig.TMDB_KEY, ApiConfig.LANGUAGE)
        mediaDao.upsertEpisodes(season.episodes.map { it.toEntity(showTmdbId, now) })
    }
}
