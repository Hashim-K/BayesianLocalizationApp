package com.example.assignment2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "oui_manufacturers")
data class OuiManufacturer(
    @PrimaryKey
    @ColumnInfo(name = "oui")
    val oui: String, // Store as "00000C" (normalized: uppercase, no colons)

    @ColumnInfo(name = "short_name")
    val shortName: String?,

    @ColumnInfo(name = "full_name")
    val fullName: String
)
