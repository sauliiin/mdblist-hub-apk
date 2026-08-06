package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------- Firebase sync

/**
 * The Firebase record shape, shared with the web app.
 *
 * This is **not** free to change: the same records are written and read by
 * the Angular build, so the two clients only stay in sync for as long as both
 * speak this exact structure — `{base, manifest, addedAt}`, matching
 * `InstalledAddon` in `core/stremio/models.ts`. The manifest travels as a raw
 * object for the same reason: whatever one client does not model, the other
 * must still get back untouched.
 */
@Serializable
data class SyncedAddonDto(
    val base: String = "",
    val manifest: JsonObject? = null,
    /** ISO-8601, which is what the web app writes. */
    val addedAt: String? = null,
)

@Serializable
data class SyncPayloadDto(
    val updatedAt: String? = null,
    val addons: List<SyncedAddonDto> = emptyList(),
)

// ------------------------------------------------------------ Stremio API

/**
 * Every call answers HTTP 200 and reports failure in the body, so the status
 * code says nothing — this `error` field is what decides.
 */
@Serializable
data class StremioErrorDto(val code: Int = 0, val message: String = "")

@Serializable
data class StremioLoginResultDto(
    val authKey: String = "",
    val user: StremioUserDto? = null,
)

@Serializable
data class StremioUserDto(val email: String? = null)

@Serializable
data class StremioLoginResponseDto(
    val result: StremioLoginResultDto? = null,
    val error: StremioErrorDto? = null,
)

/**
 * One entry of `addonCollectionGet`.
 *
 * A different shape from [SyncedAddonDto]: Stremio's own API names the field
 * `transportUrl`, not `base`, and never sends `addedAt`. Conflating the two
 * was an earlier mistake in this file — they must stay separate types.
 */
@Serializable
data class StremioCollectionEntryDto(
    val transportUrl: String? = null,
    val manifest: JsonObject? = null,
)

@Serializable
data class StremioCollectionResultDto(
    val addons: List<StremioCollectionEntryDto> = emptyList(),
)

@Serializable
data class StremioCollectionResponseDto(
    val result: StremioCollectionResultDto? = null,
    val error: StremioErrorDto? = null,
)

@Serializable
data class StremioLoginRequestDto(
    val type: String = "Login",
    val email: String,
    val password: String,
)

@Serializable
data class StremioCollectionRequestDto(
    val type: String = "AddonCollectionGet",
    val authKey: String,
    /**
     * What the official client sends. Without it the API may answer from a
     * stored snapshot, so an addon added in Stremio minutes ago never appears.
     */
    val update: Boolean = true,
)

@Serializable
data class StremioLogoutRequestDto(
    val type: String = "Logout",
    val authKey: String,
)
