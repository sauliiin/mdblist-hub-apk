package com.mdblisthub.tv.core.data

import com.mdblisthub.tv.core.data.repository.MediaRepository
import com.mdblisthub.tv.core.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Warms one title's detail the moment a card takes focus.
 *
 * Deliberately *not* a WorkManager job. WorkManager is for work that must
 * survive the process and may be deferred by hours; this is the opposite — it
 * is only useful within the next second or two, and if the user moves on it
 * should simply be dropped.
 *
 * On a remote control the focus moves in bursts as someone holds a direction
 * key, so two things guard it: a permit pool that keeps at most a few requests
 * in flight, and a record of what was already warmed.
 */
class MetadataPrefetcher(
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val warmed = ConcurrentHashMap<String, Long>()
    private val permits = Semaphore(MAX_IN_FLIGHT)

    fun prefetch(type: MediaType, tmdbId: Int): Job? {
        val key = "${type.mdblist}:$tmdbId"
        val last = warmed[key]
        if (last != null && System.currentTimeMillis() - last < REWARM_MS) return null
        warmed[key] = System.currentTimeMillis()

        return scope.launch {
            permits.withPermit {
                // A failure here is not worth reporting: the detail screen
                // will ask again and surface its own error if it matters.
                media.ensureDetail(type, tmdbId)
            }
        }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
        const val REWARM_MS = 10 * 60 * 1000L
    }
}
