package com.example.assignment2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.assignment2.data.model.MeasurementType

@Entity(tableName = "measurement_times")
data class MeasurementTime(
    @PrimaryKey(autoGenerate = true)
    val timestampId: Long = 0, // Auto-generated primary key
    val timestampMillis: Long,
    val cell: String, // e.g., "c1", "c2", "c3", "c4"
    @ColumnInfo(name = "measurement_type")
    val measurementType: MeasurementType
)
