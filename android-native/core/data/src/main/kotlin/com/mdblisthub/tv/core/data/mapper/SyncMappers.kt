package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.model.AddonRef
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.dto.StremioCollectionEntryDto
import com.mdblisthub.tv.core.network.dto.StremioManifestDto
import com.mdblisthub.tv.core.network.dto.SyncedAddonDto
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 in and out, without `java.time` — that package needs API 26, and
 * this app's floor is 24. `SimpleDateFormat` has done the job since API 1.
 */
private object Iso8601 {
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun format(epochMs: Long): String = formatter().format(java.util.Date(epochMs))

    fun parseOrNull(text: String): Long? = runCatching { formatter().parse(text)?.time }.getOrNull()
}

/**
 * Turning a manifest that arrived as a raw [JsonObject] into a row.
 *
 * Two sync sources hand manifests over this way rather than through Retrofit's
 * typed deserialiser: Firebase (this app's own past writes) and the Stremio
 * account API (a service this app does not control). Decoding lazily here,
 * instead of widening every DTO to carry a `JsonObject` field, keeps the typed
 * path — installing by URL — as the one Retrofit validates directly.
 */
private fun JsonObject.toAddonEntity(base: String, addedAt: Long): AddonEntity? {
    val manifest = runCatching {
        HttpClients.json.decodeFromJsonElement(StremioManifestDto.serializer(), this)
    }.getOrNull() ?: return null

    if (manifest.id.isBlank() || manifest.name.isBlank()) return null

    return AddonEntity(
        base = base,
        manifestJson = toString(),
        addonId = manifest.id,
        name = manifest.name,
        description = manifest.description,
        logoUrl = manifest.logo,
        version = manifest.version,
        types = manifest.types,
        resources = manifest.resources.map { it.name }.filter { it.isNotBlank() },
        idPrefixes = manifest.idPrefixes,
        configurable = manifest.behaviorHints?.configurable == true,
        addedAt = addedAt,
    )
}

/** A Firebase record — already this app's own shape, `{base, manifest}`. */
fun SyncedAddonDto.toEntityOrNull(now: Long): AddonEntity? {
    val manifest = manifest ?: return null
    val addedAtMs = addedAt?.let(Iso8601::parseOrNull)
    return manifest.toAddonEntity(base, addedAtMs ?: now)
}

fun AddonEntity.toSyncedDto() = SyncedAddonDto(
    base = base,
    manifest = runCatching {
        HttpClients.json.parseToJsonElement(manifestJson) as? JsonObject
    }.getOrNull(),
    addedAt = Iso8601.format(addedAt),
)

/**
 * One `addonCollectionGet` entry — Stremio's own shape, `{transportUrl,
 * manifest}`. Unlike a Firebase pull or a direct install, the URL still needs
 * normalising: Stremio does not guarantee it is trimmed the way this app
 * expects.
 */
fun StremioCollectionEntryDto.toEntityOrNull(now: Long): AddonEntity? {
    val url = transportUrl?.takeIf { it.isNotBlank() } ?: return null
    val base = runCatching { com.mdblisthub.tv.core.model.Addon.normaliseUrl(url) }.getOrNull() ?: return null
    return manifest?.toAddonEntity(base, now)
}

fun StremioCollectionEntryDto.ref(): AddonRef = AddonRef(
    name = manifest?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?: transportUrl ?: "(sem nome)",
    url = transportUrl ?: "(sem URL)",
)
