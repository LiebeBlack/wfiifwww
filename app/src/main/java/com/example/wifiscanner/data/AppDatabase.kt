package com.example.wifiscanner.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database holding user device labels.
 *
 * A single pre-populated instance is created lazily per process and exposed
 * via [get]. The schema version stays at 1 while we only have one table;
 * migrations should be added with `addMigrations(...)` once that changes.
 */
@Database(
    entities = [DeviceLabelEntity::class],
    version = 3,
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
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration() // dev-stage convenience; add real migrations for release
                .build()

        /** Migration from version 1 to 2: adds nickname column. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE device_labels ADD COLUMN nickname TEXT DEFAULT ''")
            }
        }

        /** Migration from version 2 to 3: changes primary key from mac to key+keyType. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create new table with new schema
                database.execSQL("""
                    CREATE TABLE device_labels_new (
                        key TEXT PRIMARY KEY NOT NULL,
                        keyType TEXT NOT NULL,
                        label TEXT NOT NULL,
                        nickname TEXT NOT NULL,
                        lastSeen INTEGER NOT NULL
                    )
                """.trimIndent())

                // Create index
                database.execSQL("CREATE INDEX index_device_labels_new_lastSeen ON device_labels_new(lastSeen)")
                database.execSQL("CREATE INDEX index_device_labels_new_keyType ON device_labels_new(keyType)")

                // Migrate existing data (all are MAC-based)
                database.execSQL("""
                    INSERT INTO device_labels_new (key, keyType, label, nickname, lastSeen)
                    SELECT mac, 'MAC', label, nickname, lastSeen FROM device_labels
                """.trimIndent())

                // Drop old table and rename new one
                database.execSQL("DROP TABLE device_labels")
                database.execSQL("ALTER TABLE device_labels_new RENAME TO device_labels")
            }
        }
    }
}
