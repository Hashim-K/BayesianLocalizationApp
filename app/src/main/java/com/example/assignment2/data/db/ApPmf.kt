package com.example.assignment2.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlin.math.floor // Add this import
import kotlin.math.max
import kotlin.math.min

@Entity(
    tableName = "ap_pmf",
    primaryKeys = ["BSSID", "cell", "binWidth"],
    foreignKeys = [
        ForeignKey(
            entity = KnownApPrime::class,
            parentColumns = ["bssid_prime"],
            childColumns = ["BSSID"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["BSSID"])]
)
data class ApPmf(
    @ColumnInfo(name = "BSSID")
    val bssidPrime: String,

    @ColumnInfo(name = "cell")
    val cell: String,

    @ColumnInfo(name = "binWidth")
    val binWidth: Int,

    @ColumnInfo(name = "bins_data")
    val binsData: Map<Int, Int>, // bin_start -> count

    @ColumnInfo(name = "min_rssi")
    val minRssi: Int = -100,

    @ColumnInfo(name = "max_rssi")
    val maxRssi: Int = 0
) {
    /**
     * Helper to get the starting RSSI value of the bin a specific RSSI value would fall into.
     */
    fun getBinStartForRssi(rssiValue: Int): Int {
        if (binWidth <= 0) return minRssi // Should not happen with valid binWidth

        // Clamp RSSI to the histogram's defined range for bin calculation consistency
        val clampedRssi = max(minRssi, min(maxRssi, rssiValue))

        // This ensures the bin starts are multiples of binWidth relative to minRssi
        return minRssi + floor((clampedRssi - minRssi).toDouble() / binWidth).toInt() * binWidth
    }
}