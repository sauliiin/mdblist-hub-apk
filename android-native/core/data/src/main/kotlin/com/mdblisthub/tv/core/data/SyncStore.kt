package com.mdblisthub.tv.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync")

/**
 * Whether the Firebase addon sync is on. Split from [SessionStore] because it
 * outlives a sign-out from mdblist — the toggle should not silently reset.
 */
class SyncStore(context: Context) {

    private val store = context.applicationContext.syncDataStore

    val firebaseSyncEnabled: Flow<Boolean> = store.data.map { it[KEY_SYNC_ON] == true }

    suspend fun setFirebaseSyncEnabled(enabled: Boolean) {
        store.edit { it[KEY_SYNC_ON] = enabled }
    }

    private companion object {
        val KEY_SYNC_ON = booleanPreferencesKey("firebase_sync_on")
    }
}
