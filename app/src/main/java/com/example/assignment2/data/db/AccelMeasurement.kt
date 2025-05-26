package com.example.assignment2.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accel_measurements")
data class AccelMeasurement(
    @PrimaryKey(autoGenerate = true)
    val accelId: Long = 0,
    val timestampMillis: Long, // Add a timestamp for when the reading was taken
    val xMaxMin: Float,
    val yMaxMin: Float,
    val zMaxMin: Float,
    val activityType: String // e.g., "Standing", "Walking", "Jumping"
)
