package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbPageDto
import com.mdblisthub.tv.core.network.dto.TmdbSeasonDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    /**
     * One request for the whole detail screen. `append_to_response` is what
     * keeps this to a single round trip instead of seven — which on a TV box
     * over Wi-Fi is the difference between a screen that opens and one that
     * assembles itself in front of you.
     */
    @GET("{type}/{id}")
    suspend fun detail(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("append_to_response") append: String,
        @Query("include_image_language") imageLanguage: String,
    ): TmdbDetailDto

    @GET("tv/{id}/season/{season}")
    suspend fun season(
        @Path("id") tmdbId: Int,
        @Path("season") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbSeasonDto

    @GET("search/multi")
    suspend fun search(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("query") query: String,
        @Query("include_adult") includeAdult: Boolean,
        @Query("page") page: Int,
    ): TmdbPageDto

    companion object {
        const val DETAIL_APPEND =
            "credits,aggregate_credits,external_ids,videos,recommendations,images,content_ratings,release_dates"

        /** `null` is TMDB's spelling for "artwork with no text on it". */
        const val IMAGE_LANGUAGES = "pt,en,null"
    }
}
