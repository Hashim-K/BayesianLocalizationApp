package com.example.assignment2.util

import android.util.Log
import com.example.assignment2.data.db.ApMeasurementDao
import com.example.assignment2.data.db.MeasurementTimeDao
import com.example.assignment2.data.model.MeasurementType // Import MeasurementType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages the creation and retrieval of RSSI histograms for different cells and BSSIDs.
 *
 * @property measurementTimeDao DAO for accessing MeasurementTime data (contains cell info).
 * @property apMeasurementDao DAO for accessing ApMeasurement data (contains bssidPrime and RSSI).
 * @property defaultBinWidth The default width for histogram bins if not specified otherwise.
 * @property defaultMinRssi Default minimum RSSI for histograms.
 * @property defaultMaxRssi Default maximum RSSI for histograms.
 */
class HistogramManager(
    private val measurementTimeDao: MeasurementTimeDao,
    private val apMeasurementDao: ApMeasurementDao,
    private val defaultBinWidth: Int = 1,
    private val defaultMinRssi: Int = -100,
    private val defaultMaxRssi: Int = 0
) {
    private val TAG = "HistogramManager"

    private var cellBssidHistograms: Map<String, Map<String, Histogram>> = emptyMap()
    private var isLoading: Boolean = false
    var isDataLoaded: Boolean = false
        private set

    /**
     * Loads TRAINING measurement data from the database and processes it to build histograms
     * for each BSSID within each cell. This is a suspend function and should be called
     * from a coroutine.
     */
    suspend fun loadAndProcessAllHistograms() {
        if (isLoading) {
            Log.d(TAG, "Histograms are already being loaded.")
            return
        }
        isLoading = true
        isDataLoaded = false
        Log.d(TAG, "Starting to load and process TRAINING histograms...")

        withContext(Dispatchers.IO) {
            try {
                // Fetch all MeasurementTime entries and then filter for TRAINING type
                val allMeasurementTimes = measurementTimeDao.getAll()
                val trainingMeasurementTimes = allMeasurementTimes.filter { it.measurementType == MeasurementType.TRAINING }

                if (trainingMeasurementTimes.isEmpty()) {
                    Log.w(TAG, "No TRAINING data found in MeasurementTime table. Cannot generate PMFs.")
                    cellBssidHistograms = emptyMap()
                    isDataLoaded = true // Data loading finished, but no data to process
                    isLoading = false
                    return@withContext
                }
                Log.d(TAG, "Found ${trainingMeasurementTimes.size} TRAINING scan events.")

                // Fetch all ApMeasurement entries once
                val allApMeasurements = apMeasurementDao.getAllRawMeasurementsOrdered()
                // Group ApMeasurements by their timestampId for efficient lookup
                val measurementsByTimestampId = allApMeasurements.groupBy { it.timestampId }

                // Temporary collector: Map<Cell, Map<BssidPrime, MutableList<RSSI>>>
                val tempDataCollector = mutableMapOf<String, MutableMap<String, MutableList<Int>>>()

                // Only process ApMeasurements linked to TRAINING MeasurementTime entries
                for (timeEntry in trainingMeasurementTimes) {
                    val cell = timeEntry.cell
                    val measurementsForThisTrainingTime = measurementsByTimestampId[timeEntry.timestampId] ?: emptyList()

                    for (apMeasurement in measurementsForThisTrainingTime) {
                        val bssidPrime = apMeasurement.bssidPrime
                        val rssi = apMeasurement.rssi

                        tempDataCollector
                            .getOrPut(cell) { mutableMapOf() }
                            .getOrPut(bssidPrime) { mutableListOf() }
                            .add(rssi)
                    }
                }

                // Now, build the actual Histogram objects
                val finalHistograms = mutableMapOf<String, MutableMap<String, Histogram>>()
                tempDataCollector.forEach { (cell, bssidMap) ->
                    val cellHistograms = mutableMapOf<String, Histogram>()
                    bssidMap.forEach { (bssidPrime, rssiList) ->
                        if (rssiList.isNotEmpty()) {
                            cellHistograms[bssidPrime] = Histogram(
                                measurements = rssiList,
                                binWidth = defaultBinWidth,
                                minRssi = defaultMinRssi,
                                maxRssi = defaultMaxRssi
                            )
                        }
                    }
                    if (cellHistograms.isNotEmpty()) {
                        finalHistograms[cell] = cellHistograms
                    }
                }
                cellBssidHistograms = finalHistograms.mapValues { it.value.toMap() }.toMap()
                isDataLoaded = true
                Log.d(TAG, "Successfully loaded and processed histograms for ${cellBssidHistograms.size} cells using TRAINING data.")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading or processing histograms", e)
                cellBssidHistograms = emptyMap()
                isDataLoaded = false
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Retrieves the histogram for a specific cell and BSSID prime.
     * Ensure `loadAndProcessAllHistograms()` has been called successfully before using this.
     *
     * @param cell The cell label.
     * @param bssidPrime The prime BSSID.
     * @return The Histogram object, or null if not found or data not loaded.
     */
    fun getHistogram(cell: String, bssidPrime: String): Histogram? {
        if (!isDataLoaded) {
            Log.w(TAG, "Attempted to get histogram, but data is not loaded. Call loadAndProcessAllHistograms() first.")
            return null
        }
        return cellBssidHistograms[cell]?.get(bssidPrime)
    }

    /**
     * Retrieves all processed histograms.
     * Ensure `loadAndProcessAllHistograms()` has been called successfully before using this.
     * @return A map where the outer key is the cell label, and the inner map's key is the BSSID prime,
     *         with the value being the Histogram object.
     */
    fun getAllHistogramsByCell(): Map<String, Map<String, Histogram>> {
        if (!isDataLoaded) {
            Log.w(TAG, "Attempted to get all histograms, but data is not loaded.")
        }
        return cellBssidHistograms
    }

    /**
     * Clears all loaded histogram data.
     */
    fun clearData() {
        cellBssidHistograms = emptyMap()
        isDataLoaded = false
        Log.d(TAG, "Cleared all loaded histogram data.")
    }
}