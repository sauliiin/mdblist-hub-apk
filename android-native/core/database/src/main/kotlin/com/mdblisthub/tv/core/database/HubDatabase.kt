package com.mdblisthub.tv.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mdblisthub.tv.core.database.dao.AddonDao
import com.mdblisthub.tv.core.database.dao.ListDao
import com.mdblisthub.tv.core.database.dao.MediaDao
import com.mdblisthub.tv.core.database.dao.PlaybackDao
import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.database.entity.EpisodeEntity
import com.mdblisthub.tv.core.database.entity.LibraryEntity
import com.mdblisthub.tv.core.database.entity.ListEntity
import com.mdblisthub.tv.core.database.entity.ListItemEntity
import com.mdblisthub.tv.core.database.entity.MediaDetailEntity
import com.mdblisthub.tv.core.database.entity.MediaEntity
import com.mdblisthub.tv.core.database.entity.ResumeEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaDetailEntity::class,
        ListEntity::class,
        ListItemEntity::class,
        EpisodeEntity::class,
        AddonEntity::class,
        ResumeEntity::class,
        LibraryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HubDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun listDao(): ListDao
    abstract fun addonDao(): AddonDao
    abstract fun playbackDao(): PlaybackDao

    companion object {
        private const val NAME = "mdblist-hub.db"

        fun create(context: Context): HubDatabase =
            Room.databaseBuilder(context.applicationContext, HubDatabase::class.java, NAME)
                // Everything in here is a cache with an upstream source of
                // truth, so throwing it away and re-syncing is a correct
                // migration for as long as that stays true.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
