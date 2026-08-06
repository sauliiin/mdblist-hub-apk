package com.mdblisthub.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.mdblisthub.tv.core.database.entity.ListEntity
import com.mdblisthub.tv.core.database.entity.ListItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {

    @Query("SELECT * FROM lists ORDER BY position ASC")
    fun observeLists(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observeList(id: Long): Flow<ListEntity?>

    @Query("SELECT * FROM lists ORDER BY position ASC")
    suspend fun lists(): List<ListEntity>

    @Upsert
    suspend fun upsertLists(lists: List<ListEntity>)

    @Query("DELETE FROM lists WHERE id NOT IN (:keep)")
    suspend fun deleteListsMissingFrom(keep: List<Long>)

    /**
     * Rewrites a list's contents as one transaction.
     *
     * Delete-then-insert rather than upsert, because a title removed from the
     * list upstream has to disappear here too — and an upsert can only ever
     * add. The transaction is what keeps the row from being seen empty
     * mid-write by the Flow the home screen is collecting.
     */
    @Transaction
    suspend fun replaceItems(listId: Long, items: List<ListItemEntity>) {
        clearItems(listId)
        insertItems(items)
    }

    @Query("DELETE FROM list_items WHERE listId = :listId")
    suspend fun clearItems(listId: Long)

    @Upsert
    suspend fun insertItems(items: List<ListItemEntity>)

    /** Appends a page without disturbing what is already there. */
    @Upsert
    suspend fun appendItems(items: List<ListItemEntity>)

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId")
    suspend fun itemCount(listId: Long): Int

    @Query("DELETE FROM lists")
    suspend fun clearLists()

    @Query("DELETE FROM list_items")
    suspend fun clearAllItems()
}
