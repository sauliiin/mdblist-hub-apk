package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/**
 * An installed Stremio addon.
 *
 * Nothing ships with the app: every addon URL is pasted by whoever is using
 * it, the same model Stremio itself uses.
 */
@Serializable
data class Addon(
    /** Transport URL with `/manifest.json` stripped — everything hangs off it. */
    val base: String,
    val id: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val version: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val configurable: Boolean = false,
    val addedAt: Long = 0L,
) {
    /**
     * Whether it is worth asking this addon for `resource` about this id.
     *
     * An addon that declares `idPrefixes` only answers for ids that start with
     * one of them, so skipping the rest is the difference between one request
     * and a dozen timeouts on every stream screen.
     */
    fun serves(resource: String, type: MediaType, id: String): Boolean {
        if (resources.none { it.equals(resource, true) }) return false
        if (types.isNotEmpty() && types.none { it.equals(type.stremio, true) }) return false
        if (idPrefixes.isEmpty()) return true
        return idPrefixes.any { id.startsWith(it) }
    }

    val configureUrl: String? get() = if (configurable) "$base/configure" else null

    companion object {
        /**
         * Accepts whatever gets pasted — `stremio://` links, a bare host, or
         * the full manifest URL — and answers the base everything else uses.
         */
        fun normaliseUrl(raw: String): String {
            var url = raw.trim()
            require(url.isNotEmpty()) { "URL vazia" }

            if (url.startsWith("stremio://", true)) url = "https://" + url.substring(10)
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            url = url.substringBefore('#').trimEnd('/')
            if (url.endsWith("/manifest.json", true)) {
                url = url.dropLast("/manifest.json".length)
            }
            require(url.length > "https://".length) { "URL inválida" }
            return url
        }
    }
}

/**
 * What an import from a Stremio account actually did.
 *
 * A bare count hides the interesting half: an addon the account has that
 * never lands here is exactly the case worth reporting, and it needs a reason
 * attached to be actionable — see `skipped`.
 */
data class ImportReport(
    /** Entries the API returned. */
    val received: Int,
    /** Names of the addons that landed. */
    val imported: List<String>,
    val skipped: List<SkippedAddon>,
    /**
     * Every entry the API answered with, untouched.
     *
     * The ground truth for "I added it in Stremio and it did not come": if an
     * addon is missing here, it never reached the account, and nothing on
     * this side would produce it.
     */
    val entries: List<AddonRef>,
)

data class SkippedAddon(val name: String, val url: String, val reason: String)
data class AddonRef(val name: String, val url: String)
