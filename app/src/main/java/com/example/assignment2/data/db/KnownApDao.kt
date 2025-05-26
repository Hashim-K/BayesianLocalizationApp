package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface KnownApDao {

    // Insert or Replace: Adds new APs, updates SSID if BSSID already exists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(knownAp: KnownAp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(knownAps: List<KnownAp>)

    @Query("SELECT * FROM known_aps WHERE bssid = :bssid LIMIT 1")
    suspend fun getKnownApByBssid(bssid: String): KnownAp?

    @Query("SELECT bssid FROM known_aps")
    suspend fun getAllKnownBssids(): List<String>

    @Query("SELECT * FROM known_aps")
    suspend fun getAllKnownAps(): List<KnownAp> // Might be useful later

    @Query("DELETE FROM known_aps")
    suspend fun clearTable()
}
