package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.mapper.ref
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.data.mapper.toEntityOrNull
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.ImportReport
import com.mdblisthub.tv.core.model.SkippedAddon
import com.mdblisthub.tv.core.network.StremioApi
import com.mdblisthub.tv.core.network.dto.StremioCollectionEntryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The addons the user installed.
 *
 * Nothing ships with the app: every URL is pasted by whoever is using it, the
 * same model Stremio uses. Re-installing an addon refreshes its manifest
 * rather than duplicating it, which is how a re-configured addon — a new
 * debrid key, say — gets updated.
 */
class AddonsRepository(
    private val api: StremioApi,
    private val database: HubDatabase,
) {
    private val dao = database.addonDao()

    fun observeAddons(): Flow<List<Addon>> =
        dao.observeAddons().map { rows -> rows.map { it.toDomain() } }

    suspend fun addons(): List<Addon> = dao.addons().map { it.toDomain() }

    /** Raw rows, manifest included — what the Firebase push needs to write. */
    suspend fun entities(): List<AddonEntity> = dao.addons()

    suspend fun install(rawUrl: String): Result<Addon> = runCatching {
        val base = Addon.normaliseUrl(rawUrl)

        val manifest = runCatching { api.manifest("$base/manifest.json") }.getOrElse {
            throw IllegalStateException(
                "Não consegui ler o manifest. Confira a URL — e note que muitos addons geram " +
                    "um endereço próprio para cada usuário na página de configuração; é esse " +
                    "que precisa ser colado aqui, não o endereço do site.",
            )
        }

        // A host's own PWA manifest has `name` but no `id`, and more than one
        // addon service serves exactly that at its root — so this is the error
        // people actually hit when they paste the site instead of the URL it
        // generated for them.
        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            throw IllegalStateException(
                "O endereço respondeu, mas não é um manifest de addon do Stremio.",
            )
        }

        val entity = manifest.toEntity(base, System.currentTimeMillis())
        dao.upsert(listOf(entity))
        entity.toDomain()
    }

    suspend fun remove(base: String) = dao.delete(base)

    /**
     * Merges a Stremio account's collection in, and reports what landed.
     *
     * The collection already carries each manifest, so nothing is re-fetched
     * — which also means an addon whose host is momentarily down still
     * imports. Merging rather than replacing keeps addons added by hand on
     * this device.
     */
    suspend fun importCollection(entries: List<StremioCollectionEntryDto>): ImportReport {
        val now = System.currentTimeMillis()
        val imported = mutableListOf<AddonEntity>()
        val skipped = mutableListOf<SkippedAddon>()

        for (entry in entries) {
            val url = entry.transportUrl
            val name = entry.ref().name

            if (url.isNullOrBlank()) {
                skipped += SkippedAddon(name, "", "a conta não guardou a URL deste addon")
                continue
            }
            if (entry.manifest == null) {
                skipped += SkippedAddon(name, url, "o manifest veio sem id")
                continue
            }

            val entity = entry.toEntityOrNull(now)
            if (entity == null) {
                skipped += SkippedAddon(name, url, "URL ou manifest que não dá para interpretar")
            } else {
                imported += entity
            }
        }

        if (imported.isNotEmpty()) {
            val bases = imported.map { it.base }.toSet()
            val existing = dao.addons().filterNot { it.base in bases }
            dao.replaceAll(existing + imported)
        }

        return ImportReport(
            received = entries.size,
            imported = imported.map { it.name },
            skipped = skipped,
            entries = entries.map { it.ref() },
        )
    }

    /**
     * Folds already-built addons in, answering how many were new to this
     * device. Backs the Firebase pull, where the entries were written by this
     * same app and need no re-validation beyond parsing.
     */
    suspend fun merge(incoming: List<AddonEntity>): Int {
        val known = dao.addons().map { it.base }.toSet()
        val fresh = incoming.count { it.base !in known }
        if (incoming.isNotEmpty()) {
            val bases = incoming.map { it.base }.toSet()
            val existing = dao.addons().filterNot { it.base in bases }
            dao.replaceAll(existing + incoming)
        }
        return fresh
    }

    /**
     * Makes this device's list exactly `next` — the "Baixar" action, which is
     * what lets a removal made elsewhere actually land here.
     */
    suspend fun replaceAll(next: List<AddonEntity>) = dao.replaceAll(next)
}
