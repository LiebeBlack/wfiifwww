package com.example.wifiscanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for per-device location labels.
 *
 * All label reads are exposed as [Flow] so the Compose UI recomposes
 * automatically whenever a label is saved — no manual refresh needed.
 */
@Dao
interface DeviceLabelDao {

    /** Every stored label, reactive. */
    @Query("SELECT * FROM device_labels")
    fun observeAll(): Flow<List<DeviceLabelEntity>>

    /** One label by MAC (non-reactive, for one-shot merges). */
    @Query("SELECT * FROM device_labels WHERE mac = :mac LIMIT 1")
    suspend fun getByMac(mac: String): DeviceLabelEntity?

    /**
     * Insert or replace a label. Saving an empty string deletes the row so
     * unlabeled devices don't accumulate stale entries.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(label: DeviceLabelEntity)

    @Query("DELETE FROM device_labels WHERE mac = :mac")
    suspend fun delete(mac: String)

    /** Remove labels not seen since [thresholdMillis] (periodic cleanup). */
    @Query("DELETE FROM device_labels WHERE lastSeen > 0 AND lastSeen < :thresholdMillis")
    suspend fun pruneStale(thresholdMillis: Long)
}
