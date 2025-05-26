package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.assignment2.data.model.MeasurementViewItem

@Dao
interface ApMeasurementDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE) // IGNORE if (bssid, timestampId) combo exists
    suspend fun insert(measurement: ApMeasurement)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(measurements: List<ApMeasurement>)

    // This query will be more complex for the DB View later,
    // as it needs to join with MeasurementTime.
    // For now, let's keep a simple version or prepare for a more complex object.
    // We'll create a new data class for the combined view later.
    @Query("SELECT * FROM ap_measurements WHERE bssid_prime = :bssid ORDER BY timestampId DESC")
    suspend fun getMeasurementsForAp(bssid: String): List<ApMeasurement>

    // Placeholder for now, will be replaced by a query returning a combined object
    // For the DB View, we'll need a new data class and a @Transaction query.
    // Let's define a temporary one that just gets raw measurements.
    @Query("SELECT * FROM ap_measurements ORDER BY bssid_prime ASC, timestampId DESC")
    suspend fun getAllRawMeasurementsOrdered(): List<ApMeasurement>

    @Transaction
    @Query("""
        SELECT
            am.bssid_prime,
            kap.ssid,
            mt.timestampMillis,
            mt.cell,
            am.rssi,
            mt.measurement_type
        FROM ap_measurements AS am
        INNER JOIN measurement_times AS mt ON am.timestampId = mt.timestampId
        LEFT JOIN known_ap_prime AS kap ON am.bssid_prime = kap.bssid_prime
        ORDER BY am.bssid_prime ASC, mt.timestampMillis DESC
    """)
    suspend fun getAllMeasurementsForView(): List<com.example.assignment2.data.model.MeasurementViewItem>

    @Query("DELETE FROM ap_measurements")
    suspend fun clearTable()

    @Query("SELECT * FROM ap_measurements WHERE timestampId = :timestampId")
    suspend fun getMeasurementsForTimestampId(timestampId: Long): List<ApMeasurement>


    }