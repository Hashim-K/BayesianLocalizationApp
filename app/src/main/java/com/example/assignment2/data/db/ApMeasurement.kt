package com.example.assignment2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "ap_measurements",
    primaryKeys = ["bssid_prime", "timestampId"],
    foreignKeys = [
        ForeignKey(
            entity = KnownApPrime::class,
            parentColumns = ["bssid_prime"],
            childColumns = ["bssid_prime"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MeasurementTime::class,
            parentColumns = ["timestampId"],
            childColumns = ["timestampId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bssid_prime"]), Index(value = ["timestampId"])]
)
data class ApMeasurement(
    @ColumnInfo(name = "bssid_prime")
    val bssidPrime: String,

    @ColumnInfo(name = "timestampId")
    val timestampId: Long,

    @ColumnInfo(name = "rssi")
    val rssi: Int
)