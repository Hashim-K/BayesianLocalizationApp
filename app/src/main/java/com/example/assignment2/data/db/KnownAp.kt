package com.example.assignment2.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.example.assignment2.data.model.ApType

@Entity(tableName = "known_aps")
data class KnownAp(
    @PrimaryKey
    @ColumnInfo(name = "bssid")
    val bssid: String,

    @ColumnInfo(name = "ssid")
    val ssid: String?,

    @ColumnInfo(name = "ap_type") // New field
    val apType: ApType
)