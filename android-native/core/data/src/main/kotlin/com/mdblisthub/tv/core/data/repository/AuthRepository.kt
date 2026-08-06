package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.model.HubUser
import com.mdblisthub.tv.core.network.MdblistApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AuthRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    val signedIn: Flow<Boolean> = session.signedIn
    val user: Flow<HubUser?> = session.user
    val isOwner: Flow<Boolean> = session.isOwner

    /**
     * Validates the key against `/user` and keeps the session only if it
     * answers. The cache is cleared first: a different account has different
     * lists, and serving the previous one's rows for a beat is worse than an
     * empty home.
     */
    suspend fun signIn(rawKey: String): Result<HubUser> = runCatching {
        val key = rawKey.trim()
        require(key.isNotEmpty()) { "Informe a chave da API." }

        val user = api.user(key).toDomain()
        clearDatabase()
        session.save(key, user)
        user
    }

    suspend fun signOut() {
        session.clear()
        clearDatabase()
    }

    /**
     * `clearAllTables()` is the one Room API in this app that is *not*
     * main-safe: the suspend DAO methods hop to Room's executor on their own,
     * but this is a plain blocking call, and the ViewModels invoke these
     * flows from the main dispatcher. Without the hop, Room refuses with
     * "Cannot access database on the main thread".
     */
    private suspend fun clearDatabase() = withContext(Dispatchers.IO) {
        database.clearAllTables()
    }

    /** Re-checks a stored key on boot; a rejected one signs the account out. */
    suspend fun restore(): Boolean {
        val key = session.currentKey()
        if (key.isBlank()) return false

        return runCatching { api.user(key).toDomain() }
            .onSuccess { session.save(key, it) }
            .onFailure { error ->
                // Only a definitive rejection ends the session. A box that
                // wakes before its Wi-Fi does must not be logged out for it.
                if (error is retrofit2.HttpException && error.code() in 401..403) signOut()
            }
            .isSuccess
    }
}
