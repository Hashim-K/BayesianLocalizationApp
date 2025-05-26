package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OuiManufacturerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) // Ignore if OUI already exists
    suspend fun insertAll(manufacturers: List<OuiManufacturer>)

    @Query("SELECT * FROM oui_manufacturers WHERE oui = :oui LIMIT 1")
    suspend fun findByOui(oui: String): OuiManufacturer?

    @Query("SELECT COUNT(*) FROM oui_manufacturers")
    suspend fun getCount(): Int

    @Query("DELETE FROM oui_manufacturers") // For testing or re-populating
    suspend fun clearTable()

    @Query("SELECT * FROM oui_manufacturers ORDER BY oui ASC")
    suspend fun getAllOuiManufacturers(): List<OuiManufacturer>
}
