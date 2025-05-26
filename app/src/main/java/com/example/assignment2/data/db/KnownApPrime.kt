package com.example.assignment2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.assignment2.data.model.ApType

@Entity(tableName = "known_ap_prime")
data class KnownApPrime(
    @PrimaryKey
    @ColumnInfo(name = "bssid_prime") // e.g., "1C281F61DF10" (normalized)
    val bssidPrime: String,

    @ColumnInfo(name = "ssid")
    val ssid: String?, // SSID from one of the BSSIDs that map to this prime

    @ColumnInfo(name = "ap_type")
    val apType: ApType // ApType from one of the BSSIDs that map to this prime
)
