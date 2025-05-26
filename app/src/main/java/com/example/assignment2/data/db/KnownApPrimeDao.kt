package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface KnownApPrimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(knownApPrime: KnownApPrime)

    @Query("SELECT * FROM known_ap_prime WHERE bssid_prime = :bssidPrime LIMIT 1")
    suspend fun findByBssidPrime(bssidPrime: String): KnownApPrime?

    @Query("SELECT * FROM known_ap_prime ORDER BY bssid_prime ASC")
    suspend fun getAll(): List<KnownApPrime>

    @Query("DELETE FROM known_ap_prime")
    suspend fun clearTable()

    @Update
    suspend fun update(knownApPrime: KnownApPrime)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(knownApPrimes: List<KnownApPrime>)
}
