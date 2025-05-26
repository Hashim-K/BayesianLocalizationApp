package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.assignment2.data.model.MeasurementTimeViewItem
import com.example.assignment2.data.model.MeasurementType

@Dao
interface MeasurementTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurementTime: MeasurementTime): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurementTimes: List<MeasurementTime>)

    @Query("SELECT * FROM measurement_times ORDER BY timestampMillis DESC")
    suspend fun getAllMeasurementTimes(): List<MeasurementTime>

    @Query("SELECT timestampId FROM measurement_times")
    suspend fun getAllTimestampIds(): List<Long>

    @Query("SELECT * FROM measurement_times WHERE timestampId = :id")
    suspend fun getMeasurementTimeById(id: Long): MeasurementTime?

    @Query("DELETE FROM measurement_times")
    suspend fun clearTable()

    @Query("SELECT COUNT(*) FROM measurement_times WHERE cell = :cell AND measurement_type = :measurementType")
    suspend fun countScanEventsForCellAndType(cell: String, measurementType: MeasurementType): Int

    @Query("SELECT * FROM measurement_times")
    suspend fun getAll(): List<MeasurementTime>

    @Transaction
    @Query("""
        SELECT
            timestampId,
            timestampMillis,
            cell,
            measurement_type
        FROM measurement_times
        ORDER BY timestampMillis DESC
    """)
    suspend fun getAllMeasurementTimesForView(): List<MeasurementTimeViewItem>
}
