package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccelMeasurementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) // Or IGNORE if you prefer
    suspend fun insert(accelMeasurement: AccelMeasurement)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accelMeasurements: List<AccelMeasurement>)

    @Query("SELECT * FROM accel_measurements ORDER BY timestampMillis DESC")
    suspend fun getAllAccelMeasurements(): List<AccelMeasurement>

    @Query("SELECT * FROM accel_measurements ORDER BY timestampMillis DESC")
    fun getAllAccelMeasurementsFlow(): Flow<List<AccelMeasurement>>

    @Query("DELETE FROM accel_measurements")
    suspend fun clearTable()
}
