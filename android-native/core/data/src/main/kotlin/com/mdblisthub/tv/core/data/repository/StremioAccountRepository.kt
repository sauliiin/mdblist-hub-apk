package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.StremioSession
import com.mdblisthub.tv.core.data.SyncStore
import com.mdblisthub.tv.core.model.ImportReport
import com.mdblisthub.tv.core.network.StremioAccountApi
import com.mdblisthub.tv.core.network.dto.StremioCollectionRequestDto
import com.mdblisthub.tv.core.network.dto.StremioLoginRequestDto
import com.mdblisthub.tv.core.network.dto.StremioLogoutRequestDto
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Signing in to a Stremio account to pull its addon collection.
 *
 * Addons otherwise live only in this device's database — so the same person
 * on a phone and on a TV box starts empty on each. Stremio already keeps the
 * collection server-side; the password is posted once, straight to
 * `api.strem.io`, and never stored — what is kept is the `authKey` it hands
 * back.
 */
class StremioAccountRepository(
    private val api: StremioAccountApi,
    private val store: SyncStore,
    private val addons: AddonsRepository,
) {
    val session: Flow<StremioSession?> = store.stremioSession

    /** Signs in and immediately imports the collection. */
    suspend fun login(email: String, password: String): Result<ImportReport> = runCatching {
        val response = call { api.login(StremioLoginRequestDto(email = email.trim(), password = password)) }
        response.error?.let { throw IllegalStateException(translate(it.message)) }
        val result = response.result?.takeIf { it.authKey.isNotBlank() }
            ?: throw IllegalStateException("A API do Stremio não devolveu uma sessão.")

        val session = StremioSession(result.authKey, result.user?.email ?: email.trim())
        store.saveStremioSession(session)
        importFor(session.authKey)
    }

    /** Re-reads the collection for the session already stored. */
    suspend fun sync(): Result<ImportReport> = runCatching {
        val session = store.currentStremioSession()
            ?: throw IllegalStateException("Entre na sua conta Stremio primeiro.")
        importFor(session.authKey)
    }

    /** Drops the session here. The addons already imported stay put. */
    suspend fun logout() {
        val session = store.currentStremioSession()
        store.clearStremioSession()
        if (session != null) {
            // Nothing downstream depends on the server agreeing; the key is
            // already forgotten on this side.
            runCatching { api.logout(StremioLogoutRequestDto(authKey = session.authKey)) }
        }
    }

    private suspend fun importFor(authKey: String): ImportReport {
        val response = call {
            api.collection(StremioCollectionRequestDto(authKey = authKey, update = true))
        }
        response.error?.let { error ->
            // The only error worth special handling: an expired or revoked key.
            if (error.message.contains("session", ignoreCase = true)) {
                store.clearStremioSession()
                throw IllegalStateException("Sua sessão do Stremio expirou. Entre novamente.")
            }
            throw IllegalStateException(translate(error.message))
        }
        val result = response.result ?: throw IllegalStateException("Resposta inesperada da API do Stremio.")
        return addons.importCollection(result.addons)
    }

    private suspend fun <T> call(request: suspend () -> T): T = try {
        request()
    } catch (_: IOException) {
        throw IllegalStateException("Não foi possível falar com a API do Stremio.")
    }
}

/** The API's messages are English and terse; these are the ones users hit. */
private fun translate(message: String): String = when (message) {
    "Wrong passphrase" -> "Senha incorreta."
    "User not found" -> "Não existe conta Stremio com esse e-mail."
    "Session does not exist" -> "Sessão do Stremio expirada. Entre novamente."
    else -> message
}
