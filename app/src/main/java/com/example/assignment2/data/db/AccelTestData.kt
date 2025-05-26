package com.example.assignment2.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accel_test_data")
data class AccelTestData(
    @PrimaryKey(autoGenerate = true)
    val testId: Long = 0,
    val timestampMillis: Long,
    val xMaxMin: Float,
    val yMaxMin: Float,
    val zMaxMin: Float,
    val actualActivityType: String // Ground truth label
)
