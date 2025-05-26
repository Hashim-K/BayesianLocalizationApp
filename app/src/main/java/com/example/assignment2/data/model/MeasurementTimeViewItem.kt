package com.example.assignment2.data.model

import androidx.room.ColumnInfo
import com.example.assignment2.data.model.MeasurementType

data class MeasurementTimeViewItem(
    @ColumnInfo(name = "timestampId")
    val timestampId: Long,

    @ColumnInfo(name = "timestampMillis")
    val timestampMillis: Long,

    @ColumnInfo(name = "cell")
    val cell: String,

    @ColumnInfo(name = "measurement_type")
    val measurementType: MeasurementType?
)
