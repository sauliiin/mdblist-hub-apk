package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.BucketResponseDto
import com.mdblisthub.tv.core.network.dto.LibraryWriteDto
import com.mdblisthub.tv.core.network.dto.MdbInfoDto
import com.mdblisthub.tv.core.network.dto.MdbItemDto
import com.mdblisthub.tv.core.network.dto.MdbListDto
import com.mdblisthub.tv.core.network.dto.MdbUserDto
import com.mdblisthub.tv.core.network.dto.PlaybackSessionDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * mdblist.
 *
 * Worth noting against the web build: the library writes here are plain JSON
 * POSTs. In a browser they need a proxy, because a cross-origin JSON POST
 * triggers a CORS preflight and mdblist answers OPTIONS with 405. A native
 * client has no such rule, so the proxy the web app needs simply does not
 * exist in this one.
 */
interface MdblistApi {

    @GET("user")
    suspend fun user(@Query("apikey") apiKey: String): MdbUserDto

    @GET("lists/user")
    suspend fun lists(@Query("apikey") apiKey: String): List<MdbListDto>

    /** `unified=true` flattens films and shows, which the mixed lists need. */
    @GET("lists/{id}/items")
    suspend fun listItems(
        @Path("id") listId: Long,
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("unified") unified: Boolean,
        @Query("append_to_response") append: String,
    ): List<MdbItemDto>

    /** Aggregated ratings and mirrored reviews for one title, keyed by TMDB id. */
    @GET("tmdb/{type}/{id}")
    suspend fun info(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("apikey") apiKey: String,
        @Query("append_to_response") append: String,
    ): MdbInfoDto

    @GET
    suspend fun bucket(@Url url: String, @Query("apikey") apiKey: String): BucketResponseDto

    @POST
    suspend fun bucketWrite(
        @Url url: String,
        @Query("apikey") apiKey: String,
        @Body body: LibraryWriteDto,
    ): Response<ResponseBody>

    /**
     * Scrobbling goes out form-encoded with the target in bracket notation
     * (`movie[ids][imdb]`), which is the shape mdblist's schema documents.
     */
    @FormUrlEncoded
    @POST("scrobble/{action}")
    suspend fun scrobble(
        @Path("action") action: String,
        @Query("apikey") apiKey: String,
        @FieldMap fields: Map<String, String>,
    ): Response<ResponseBody>

    /** Paused sessions, which is what makes a title resumable across devices. */
    @GET("sync/playback")
    suspend fun playback(@Query("apikey") apiKey: String): List<PlaybackSessionDto>
}
