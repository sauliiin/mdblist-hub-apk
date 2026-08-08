package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.SyncStore
import com.mdblisthub.tv.core.data.SyncTokens
import com.mdblisthub.tv.core.data.mapper.toEntityOrNull
import com.mdblisthub.tv.core.data.mapper.toSyncedDto
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.SyncApi
import com.mdblisthub.tv.core.network.dto.SyncPayloadDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cross-device addon sync over the Firebase Realtime Database REST interface
 * — the same store the web build writes, keyed the same way (see
 * [SyncTokens]), so turning this on here picks up whatever was installed
 * there.
 */
class FirebaseSyncRepository(
    private val api: SyncApi,
    private val store: SyncStore,
    private val session: SessionStore,
    private val addons: AddonsRepository,
    private val scope: CoroutineScope,
) {
    val enabled: Flow<Boolean> = store.firebaseSyncEnabled

    private val busyState = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = busyState.asStateFlow()

    private val failureState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = failureState.asStateFlow()

    private val lastState = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = lastState.asStateFlow()

    /** Set while a pull is being applied, so it does not echo straight back. */
    @Volatile private var applying = false
    private var pushJob: Job? = null

    init {
        // Any local change — installed, removed, imported from Stremio —
        // becomes a write, debounced so a bulk import is one request.
        scope.launch {
            combine(store.firebaseSyncEnabled, addons.observeAddons()) { on, _ -> on }
                .collect { on ->
                    if (!on || applying) return@collect
                    val key = session.currentKey()
                    if (key.isBlank()) return@collect

                    pushJob?.cancel()
                    pushJob = scope.launch {
                        delay(PUSH_DELAY_MS)
                        runCatching { write(key, addons.entities().map { it.toSyncedDto() }) }
                    }
                }
        }
    }

    /**
     * Turns sync on by joining the two lists: whatever is stored plus
     * whatever this device already had, pushed back so both sides agree.
     */
    suspend fun enable(): Result<Int> = request {
        store.setFirebaseSyncEnabled(true)
        val key = requireKey()
        val remote = read(key)

        // The write-back has to sit inside the same guarded block as the
        // merge: Room's change notification can lag a beat behind the DAO
        // call returning, and closing the guard right after `merge()` leaves
        // a window where that notification reaches the reactive pusher below
        // and queues a redundant write.
        applyLocally {
            val fresh = addons.merge(remote)
            write(key, addons.entities().map { it.toSyncedDto() })
            fresh
        }
    }

    suspend fun disable() {
        store.setFirebaseSyncEnabled(false)
        pushJob?.cancel()
        failureState.value = null
    }

    /**
     * Makes this device match what is stored, removals included.
     *
     * A replace, not a merge: `push` writes the whole list, so an addon
     * removed elsewhere is simply absent from the stored copy. Merging here
     * would read that absence as "nothing to add", and the deletion would
     * never arrive.
     *
     * An empty read is the one case that is *not* applied. It is ambiguous —
     * a genuinely empty cloud list looks identical to a key that was never
     * written, a partially-parsed payload, or a Firebase answer of `null` —
     * and every one of those, replaced in, silently wipes a working set of
     * addons and leaves nothing behind. Since the destructive reading is
     * indistinguishable from the harmless one, the local list is kept and the
     * caller is told why rather than guessing.
     */
    suspend fun pull(): Result<Int> = request {
        val key = requireKey()
        val remote = read(key)
        check(remote.isNotEmpty()) {
            "A nuvem não devolveu nenhum addon, então não mexi nos daqui. " +
                "Use \"Enviar\" no aparelho que tem os addons certos primeiro."
        }

        val before = addons.entities().size
        applyLocally { addons.replaceAll(remote) }
        kotlin.math.abs(remote.size - before)
    }

    /** Writes this device's list out, replacing whatever was stored. */
    suspend fun push(): Result<Int> = request {
        val key = requireKey()
        val entities = addons.entities()
        write(key, entities.map { it.toSyncedDto() })
        entities.size
    }

    private suspend fun read(apiKey: String) =
        (api.read(url(apiKey))?.addons ?: emptyList())
            .mapNotNull { it.toEntityOrNull(System.currentTimeMillis()) }

    private suspend fun write(apiKey: String, entries: List<com.mdblisthub.tv.core.network.dto.SyncedAddonDto>) {
        api.write(url(apiKey), SyncPayloadDto(updatedAt = nowIso(), addons = entries))
    }

    private fun url(apiKey: String) =
        "${ApiConfig.FIREBASE_BASE}${ApiConfig.FIREBASE_ROOT}/${SyncTokens.forApiKey(apiKey)}.json"

    /**
     * Runs a local mutation with the write-back guard held. The guard has to
     * span the mutation itself, not just the call, or the reactive push above
     * would send straight back what was only just read.
     */
    private suspend fun <T> applyLocally(mutate: suspend () -> T): T {
        applying = true
        try {
            return mutate()
        } finally {
            applying = false
        }
    }

    private suspend fun requireKey(): String {
        val key = session.currentKey()
        require(key.isNotBlank()) { "Entre com sua chave do mdblist primeiro." }
        return key
    }

    private suspend fun request(call: suspend () -> Int): Result<Int> {
        busyState.value = true
        failureState.value = null

        val result = runCatching { call() }
        busyState.value = false

        result.onSuccess { lastState.value = nowIso() }
        result.onFailure { failureState.value = it.message ?: "Não foi possível falar com o Firebase." }
        return result
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

    private companion object {
        /** Collapses a burst of installs into a single write. */
        const val PUSH_DELAY_MS = 1500L
    }
}
