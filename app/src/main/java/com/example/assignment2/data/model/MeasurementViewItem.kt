package com.example.assignment2.data.model

import androidx.room.ColumnInfo

// Data class to hold combined information for display
data class MeasurementViewItem(
    @ColumnInfo(name = "bssid_prime")
    val bssidPrime: String,

    @ColumnInfo(name = "ssid")
    val ssid: String?,

    @ColumnInfo(name = "timestampMillis")
    val timestampMillis: Long,

    @ColumnInfo(name = "cell")
    val cell: String,

    @ColumnInfo(name = "rssi")
    val rssi: Int,

    @ColumnInfo(name = "measurement_type")
    val measurementType: MeasurementType
)
