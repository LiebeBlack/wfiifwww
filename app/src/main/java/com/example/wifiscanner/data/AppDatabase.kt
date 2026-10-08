package com.example.wifiscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database holding user device labels.
 *
 * A single pre-populated instance is created lazily per process and exposed
 * via [get]. The schema version stays at 1 while we only have one table;
 * migrations should be added with `addMigrations(...)` once that changes.
 */
@Database(
    entities = [DeviceLabelEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun deviceLabelDao(): DeviceLabelDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Thread-safe singleton accessor. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "wifi_scanner.db",
            ).fallbackToDestructiveMigration() // dev-stage convenience; add real migrations for release
                .build()
    }
}
