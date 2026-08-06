package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mdblisthub.tv.core.model.HubUser
import com.mdblisthub.tv.core.network.ApiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/**
 * The signed-in account.
 *
 * The API key belongs to whoever typed it and never ships in the binary, so
 * this is the only place it lives. DataStore rather than a Room table because
 * it has to be readable before the database is opened — the very first thing a
 * cold start does is decide whether to show the login screen.
 */
class SessionStore(context: Context) {

    private val store = context.applicationContext.sessionDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val apiKey: Flow<String> = store.data.map { it[KEY_API].orEmpty() }

    val user: Flow<HubUser?> = store.data.map { prefs ->
        prefs[KEY_USER]?.let { raw -> runCatching { json.decodeFromString<HubUser>(raw) }.getOrNull() }
    }

    /** Only the owner account gets the curated home; see [ListCatalog]. */
    val isOwner: Flow<Boolean> = user.map {
        it?.username?.lowercase() == ApiConfig.OWNER_USERNAME
    }

    val signedIn: Flow<Boolean> = apiKey.map { it.isNotBlank() }

    suspend fun currentKey(): String = apiKey.first()

    suspend fun currentUser(): HubUser? = user.first()

    suspend fun save(key: String, user: HubUser) {
        store.edit {
            it[KEY_API] = key
            it[KEY_USER] = json.encodeToString(user)
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_API = stringPreferencesKey("api_key")
        val KEY_USER = stringPreferencesKey("user")
    }
}
