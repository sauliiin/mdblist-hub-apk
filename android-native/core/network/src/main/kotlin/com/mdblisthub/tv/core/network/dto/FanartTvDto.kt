package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class FanartTvDto(
    val name: String? = null,
    val thetvdb_id: String? = null,
    val tmdb_id: String? = null,
    val moviebackground: List<FanartTvImageDto>? = null,
    val showbackground: List<FanartTvImageDto>? = null,
)

@Serializable
data class FanartTvImageDto(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null,
    val likes: String? = null,
)
