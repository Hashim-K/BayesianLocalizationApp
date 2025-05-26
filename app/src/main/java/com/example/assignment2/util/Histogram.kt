package com.example.assignment2.util

import kotlin.math.floor

/**
 * Represents a histogram of RSSI values.
 *
 * @property measurements The list of RSSI values to build the histogram from.
 * @property binWidth The width of each bin in the histogram.
 * @property minRssi The minimum RSSI value considered for the histogram range.
 *                     Values below this might be ignored or clamped depending on implementation.
 * @property maxRssi The maximum RSSI value considered for the histogram range.
 *                     Values above this might be ignored or clamped.
 */
class Histogram(
    measurements: List<Int>,
    val binWidth: Int,
    private val minRssi: Int = -100, // Default typical RSSI min
    private val maxRssi: Int = 0     // Default typical RSSI max
) {
    // Stores the histogram as a map where:
    // Key = the starting RSSI value of the bin (e.g., -100, -95, -90 for binWidth 5)
    // Value = the count of measurements falling into that bin
    val bins: Map<Int, Int>

    // Total number of measurements used to build this histogram (after filtering if any)
    val totalCount: Int

    init {
        if (binWidth <= 0) {
            throw IllegalArgumentException("Bin width must be positive.")
        }
        val mutableBins = mutableMapOf<Int, Int>()
        var currentTotalCount = 0

        // Initialize all possible bins within the range to 0 count
        // This ensures bins with no measurements are still represented if needed.
        var currentBinStart = minRssi
        while (currentBinStart <= maxRssi) {
            mutableBins[currentBinStart] = 0
            currentBinStart += binWidth
        }

        for (rssi in measurements) {
            // Optional: Filter or clamp measurements outside the defined range
            if (rssi < minRssi || rssi > maxRssi) {
                // Log.w("Histogram", "Measurement $rssi out of range [$minRssi, $maxRssi]. Ignoring.")
                continue
            }
            currentTotalCount++

            // Calculate the starting value of the bin this RSSI falls into
            val binStartValue = minRssi + floor((rssi - minRssi).toDouble() / binWidth).toInt() * binWidth

            // Ensure the calculated binStartValue is a valid key (it should be if logic is correct)
            // and increment its count.
            // If a measurement is exactly maxRssi, it should fall into the last bin correctly.
            // If maxRssi itself is a bin start, it's fine.
            // If rssi = maxRssi, and maxRssi is not a bin start, it should fall into the bin that *contains* maxRssi.
            // Example: min=-100, max=0, binWidth=5. maxRssi=0. Bin for 0 is 0.
            // Example: min=-100, max=-1, binWidth=5. maxRssi=-1. Bin for -1 is -5.
            // The floor logic handles this correctly.

            mutableBins[binStartValue] = (mutableBins[binStartValue] ?: 0) + 1
        }
        this.bins = mutableBins.toMap() // Make it immutable
        this.totalCount = currentTotalCount
    }

    /**
     * Gets the count for the bin that a specific RSSI value would fall into.
     */
    fun getCountForRssi(rssiValue: Int): Int {
        if (rssiValue < minRssi || rssiValue > maxRssi) {
            return 0 // Outside defined range
        }
        val binStartValue = minRssi + floor((rssiValue - minRssi).toDouble() / binWidth).toInt() * binWidth
        return bins[binStartValue] ?: 0
    }

    /**
     * Calculates the Probability Mass Function (PMF) for the histogram.
     * @return A map where Key is the bin start and Value is the probability.
     */
    fun getPmf(): Map<Int, Double> {
        if (totalCount == 0) {
            return bins.mapValues { 0.0 } // Avoid division by zero, all probabilities are 0
        }
        return bins.mapValues { (_, count) ->
            count.toDouble() / totalCount
        }
    }

    override fun toString(): String {
        return "Histogram(binWidth=$binWidth, totalCount=$totalCount, bins=$bins)"
    }

    /**
     * Calculates the approximate average RSSI of the histogram.
     * This is done by (bin_center * probability_of_bin) summed over all bins.
     * Bin center is approximated as (bin_start + bin_width / 2.0).
     * @return The calculated average RSSI, or a default value (like minRssi) if totalCount is 0.
     */
    fun getApproximateAverageRssi(): Double {
        if (totalCount == 0) {
            return minRssi.toDouble() // Or Double.NEGATIVE_INFINITY or some other indicator
        }
        val pmf = getPmf()
        var averageRssi = 0.0
        pmf.forEach { (binStart, probability) ->
            val binCenter = binStart + (binWidth / 2.0)
            averageRssi += binCenter * probability
        }
        return averageRssi
    }
}
