package com.mdblisthub.tv.core.data.repository

import com.mdblisthub.tv.core.data.CachePolicy
import com.mdblisthub.tv.core.data.ListCatalog
import com.mdblisthub.tv.core.data.SessionStore
import com.mdblisthub.tv.core.data.mapper.toDomain
import com.mdblisthub.tv.core.data.mapper.toEntity
import com.mdblisthub.tv.core.database.HubDatabase
import com.mdblisthub.tv.core.database.entity.ListItemEntity
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.network.MdblistApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The home screen's rows.
 *
 * Every read comes out of Room, never off the wire: `observeLists` and
 * `observeItems` emit whatever was last synced, immediately, and a refresh
 * writes over it in the background. That is what makes the home paint on the
 * first frame of a cold start instead of after twenty-five requests.
 */
class ListsRepository(
    private val api: MdblistApi,
    private val session: SessionStore,
    private val database: HubDatabase,
) {
    private val listDao = database.listDao()
    private val mediaDao = database.mediaDao()

    fun observeLists(): Flow<List<MediaList>> =
        listDao.observeLists().map { rows -> rows.map { it.toDomain() } }

    /** A one-shot read of the cached rows, for the workers. */
    suspend fun listsOnce(): List<MediaList> = listDao.lists().map { it.toDomain() }

    fun observeItems(listId: Long): Flow<List<MediaItem>> =
        mediaDao.observeListItems(listId).map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshLists(force: Boolean = false): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val existing = listDao.lists()
        if (!force && existing.isNotEmpty() &&
            existing.none { CachePolicy.isStale(it.fetchedAt, CachePolicy.LISTS_MS) }
        ) return@runCatching

        val now = System.currentTimeMillis()
        val isOwner = session.isOwner.first()
        val arranged = ListCatalog.arrange(api.lists(key), isOwner)

        listDao.upsertLists(
            arranged.mapIndexed { index, (dto, displayName) -> dto.toEntity(displayName, index, now) },
        )
        // A list deleted upstream, or one that fell out of the curated set,
        // has to stop being a row here too.
        listDao.deleteListsMissingFrom(arranged.map { it.first.id })
    }

    /**
     * Pulls the first page of a list. `replace` rather than merge, because a
     * title removed upstream must disappear — an upsert can only ever add.
     */
    suspend fun refreshItems(listId: Long, force: Boolean = false): Result<Unit> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching

        val cached = listDao.observeList(listId).first()
        if (!force && cached != null && listDao.itemCount(listId) > 0 &&
            !CachePolicy.isStale(cached.fetchedAt, CachePolicy.LIST_ITEMS_MS)
        ) return@runCatching

        val now = System.currentTimeMillis()
        val items = api.listItems(listId, key, PAGE_SIZE, 0, unified = true, append = APPEND)

        mediaDao.upsert(items.map { it.toEntity(now) })
        listDao.replaceItems(
            listId,
            items.mapIndexed { index, dto ->
                val entity = dto.toEntity(now)
                ListItemEntity(listId, entity.tmdbId, entity.type, index)
            },
        )
    }

    /** Appends the next page, for a row scrolled to its end. Answers how many. */
    suspend fun loadMore(listId: Long): Result<Int> = runCatching {
        val key = session.currentKey()
        if (key.isBlank()) return@runCatching 0

        val offset = listDao.itemCount(listId)
        val now = System.currentTimeMillis()
        val items = api.listItems(listId, key, PAGE_SIZE, offset, unified = true, append = APPEND)
        if (items.isEmpty()) return@runCatching 0

        mediaDao.upsert(items.map { it.toEntity(now) })
        listDao.appendItems(
            items.mapIndexed { index, dto ->
                val entity = dto.toEntity(now)
                ListItemEntity(listId, entity.tmdbId, entity.type, offset + index)
            },
        )
        items.size
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val APPEND = "poster,genre,ratings"
    }
}
