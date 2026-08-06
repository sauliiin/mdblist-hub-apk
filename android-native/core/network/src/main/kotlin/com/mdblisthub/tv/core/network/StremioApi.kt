package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.StremioManifestDto
import com.mdblisthub.tv.core.network.dto.StremioStreamsDto
import com.mdblisthub.tv.core.network.dto.StremioSubtitlesDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Addons live at whatever host the user pasted, so every call takes a full
 * URL rather than a path against a base.
 */
interface StremioApi {

    @GET
    suspend fun manifest(@Url url: String): StremioManifestDto

    @GET
    suspend fun streams(@Url url: String): StremioStreamsDto

    @GET
    suspend fun subtitles(@Url url: String): StremioSubtitlesDto
}
