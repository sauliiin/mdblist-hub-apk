package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.FanartTvDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface FanartTvApi {

    @GET("{type}/{id}")
    suspend fun art(
        @Path("type") type: String,
        @Path("id") id: Int,
        @Header("api-key") apiKey: String,
    ): FanartTvDto

}
