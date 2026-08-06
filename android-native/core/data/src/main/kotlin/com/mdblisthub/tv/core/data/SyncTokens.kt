package com.mdblisthub.tv.core.data

import java.security.MessageDigest

/**
 * The path segment a device's addon list is stored under in Firebase:
 * SHA-256 of the mdblist API key, exactly as the web build derives it
 * (`mdblist-hub:${apiKey}` through `crypto.subtle.digest`).
 *
 * Keying on the key rather than on the account id means the path is itself a
 * secret — someone who knows a username still cannot construct it. That only
 * holds up if the database rules also refuse to list the children of
 * `mdblist-hub/addons`; unlike the browser, there is no secure-context
 * restriction here, so no fallback path is needed for a bare-HTTP LAN address.
 */
object SyncTokens {
    fun forApiKey(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("mdblist-hub:$apiKey".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
