package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/** A signed-in Stremio account: the session key it returned, never the password. */
data class StremioSession(val authKey: String, val email: String)

/**
 * The two cross-device features that are not the mdblist session itself:
 * whether Firebase sync is on, and whether a Stremio account is connected.
 * Split from [SessionStore] because both outlive a sign-out from mdblist —
 * disconnecting one account should not silently drop the other.
 */
class SyncStore(context: Context) {

    private val store = context.applicationContext.syncDataStore

    val firebaseSyncEnabled: Flow<Boolean> = store.data.map { it[KEY_SYNC_ON] == true }

    val stremioSession: Flow<StremioSession?> = store.data.map { prefs ->
        val key = prefs[KEY_STREMIO_AUTH]
        if (key.isNullOrBlank()) null else StremioSession(key, prefs[KEY_STREMIO_EMAIL].orEmpty())
    }

    suspend fun currentStremioSession(): StremioSession? = stremioSession.first()

    suspend fun setFirebaseSyncEnabled(enabled: Boolean) {
        store.edit { it[KEY_SYNC_ON] = enabled }
    }

    suspend fun saveStremioSession(session: StremioSession) {
        store.edit {
            it[KEY_STREMIO_AUTH] = session.authKey
            it[KEY_STREMIO_EMAIL] = session.email
        }
    }

    suspend fun clearStremioSession() {
        store.edit {
            it.remove(KEY_STREMIO_AUTH)
            it.remove(KEY_STREMIO_EMAIL)
        }
    }

    private companion object {
        val KEY_SYNC_ON = booleanPreferencesKey("firebase_sync_on")
        val KEY_STREMIO_AUTH = stringPreferencesKey("stremio_auth_key")
        val KEY_STREMIO_EMAIL = stringPreferencesKey("stremio_email")
    }
}
