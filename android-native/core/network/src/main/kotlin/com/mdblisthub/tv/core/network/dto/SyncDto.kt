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
