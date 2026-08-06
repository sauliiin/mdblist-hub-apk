package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.StremioCollectionRequestDto
import com.mdblisthub.tv.core.network.dto.StremioCollectionResponseDto
import com.mdblisthub.tv.core.network.dto.StremioLoginRequestDto
import com.mdblisthub.tv.core.network.dto.StremioLoginResponseDto
import com.mdblisthub.tv.core.network.dto.StremioLogoutRequestDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * A Stremio account, for pulling its addon collection.
 *
 * Every call answers HTTP 200 and reports failure in the body, so the status
 * code says nothing — the `error` field is what decides. The password is
 * posted once and never stored; what is kept is the `authKey` it returns.
 */
interface StremioAccountApi {

    @POST("login")
    suspend fun login(@Body body: StremioLoginRequestDto): StremioLoginResponseDto

    @POST("addonCollectionGet")
    suspend fun collection(@Body body: StremioCollectionRequestDto): StremioCollectionResponseDto

    @POST("logout")
    suspend fun logout(@Body body: StremioLogoutRequestDto): Response<ResponseBody>
}
