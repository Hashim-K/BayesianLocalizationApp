package com.example.assignment2.util

import android.util.Log
import com.example.assignment2.data.db.ApPmf
import com.example.assignment2.data.db.ApPmfDao
import com.example.assignment2.data.db.KnownApPrimeDao
import com.example.assignment2.data.model.ApType
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.BayesianSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Performs Bayesian location prediction using pre-calculated PMF data.
 * This class encapsulates the core Bayesian estimation logic.
 *
 * @param apPmfDao DAO for accessing stored PMF data.
 * @param knownApPrimeDao DAO for accessing Known AP Prime data (to filter fixed APs).
 */
class BayesianPredictor(
    private val apPmfDao: ApPmfDao,
    private val knownApPrimeDao: KnownApPrimeDao
) {

    private val TAG = "BayesianPredictor"
    private val DEFAULT_RSSI = -100 // Consistent with ApMeasurement default
    private val LIKELIHOOD_FOR_MISSING_AP_MODEL = 0.00001


    /**
     * Executes the Bayesian prediction based on the provided live RSSI scan and settings.
     *
     * @param liveRssiMap A map of detected BSSID Primes to their RSSI values from the current scan.
     * @param settings The Bayesian prediction settings (mode, bin width, etc.).
     * @param allPossibleCells A list of all possible cell labels (e.g., C1, C2, ...).
     * @return A map of cell labels to their posterior probabilities. Returns empty map if prediction is not possible.
     */
    suspend fun predict(
        liveRssiMap: Map<String, Int>,
        settings: BayesianSettings,
        allPossibleCells: List<String>
    ): Map<String, Double> {

        val allPmfsForBinWidth = withContext(Dispatchers.IO) {
            apPmfDao.getAllPmfs()
                .filter { it.binWidth == settings.pmfBinWidth }
        }.groupBy { it.bssidPrime }

        if (allPmfsForBinWidth.isEmpty()) {
            Log.w(TAG, "No PMF data found for bin width ${settings.pmfBinWidth}. Cannot predict.")
            return emptyMap()
        }

        val allFixedApBssidsInModel = withContext(Dispatchers.IO) {
            knownApPrimeDao.getAll()
                .filter { it.apType == ApType.FIXED }
                .map { it.bssidPrime }
                .toSet()
        }

        if (allFixedApBssidsInModel.isEmpty()) {
            Log.w(TAG, "No fixed APs found in the model. Cannot predict.")
            return emptyMap()
        }

        return when (settings.mode) {
            BayesianMode.PARALLEL -> calculateParallelBayesian(
                liveRssiMap,
                allPmfsForBinWidth,
                allPossibleCells,
                allFixedApBssidsInModel
            )
            BayesianMode.SERIAL -> calculateSerialBayesian(
                liveRssiMap,
                allPmfsForBinWidth,
                allPossibleCells,
                allFixedApBssidsInModel,
                settings.serialCutoffProbability // Pass the new cutoff
            )
        }
    }

    private fun getLikelihood(
        bssidPrime: String,
        cell: String,
        observedRssi: Int,
        apPmf: ApPmf?
    ): Double {
        if (apPmf == null || apPmf.binsData.isEmpty()) {
            Log.w(TAG, "PMF data is null or empty for AP $bssidPrime in Cell $cell. Returning default likelihood for missing model.")
            return LIKELIHOOD_FOR_MISSING_AP_MODEL
        }

        val binsData = apPmf.binsData
        val totalCount = binsData.values.sum()

        if (totalCount == 0) {
            Log.d(TAG, "Total count in PMF is zero for AP $bssidPrime in Cell $cell. Likelihood is 0.0.")
            return 0.0
        }

        val relevantBinStart = apPmf.getBinStartForRssi(observedRssi)
        val countInBin = binsData[relevantBinStart] ?: 0

        val likelihood = countInBin.toDouble() / totalCount.toDouble()

        if (likelihood == 0.0) {
            Log.d(TAG, "Likelihood is 0.0 for AP $bssidPrime (RSSI $observedRssi, Bin $relevantBinStart) in Cell $cell. (Bin had 0 counts).")
        }
        return likelihood
    }

    private fun calculateParallelBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>
    ): Map<String, Double> {
        val posterior = mutableMapOf<String, Double>()
        val numCells = allCells.size
        val initialPrior = 1.0 / numCells

        for (cell in allCells) {
            var cellLikelihoodProduct = 1.0

            for (bssidPrimeInModel in allFixedApBssidsInModel) {
                val observedRssi = liveRssiMap.getOrDefault(bssidPrimeInModel, DEFAULT_RSSI)
                val apPmfForCell = pmfsByBssid[bssidPrimeInModel]?.find { it.cell == cell }

                val likelihood = getLikelihood(bssidPrimeInModel, cell, observedRssi, apPmfForCell)

                cellLikelihoodProduct *= if (likelihood == 0.0) 1.0 else likelihood
            }
            posterior[cell] = cellLikelihoodProduct * initialPrior
        }
        return normalizeProbabilities(posterior)
    }

    private fun calculateSerialBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>,
        cutoffProbability: Double // New parameter for cutoff
    ): Map<String, Double> {
        val numCells = allCells.size
        var currentPriors = allCells.associateWith { 1.0 / numCells }.toMutableMap()

        val sortedFixedApBssidsToProcess = allFixedApBssidsInModel.toList().sortedByDescending { liveRssiMap.getOrDefault(it, DEFAULT_RSSI) }

        Log.d(TAG, "Serial Bayesian AP processing order:")
        sortedFixedApBssidsToProcess.forEachIndexed { index, bssidPrime ->
            val rssi = liveRssiMap.getOrDefault(bssidPrime, DEFAULT_RSSI)
            Log.d(TAG, "  ${index + 1}. BSSID: $bssidPrime, RSSI: $rssi dBm")
        }

        for (bssidPrime in sortedFixedApBssidsToProcess) {
            val observedRssi = liveRssiMap.getOrDefault(bssidPrime, DEFAULT_RSSI)
            val nextPosteriorsUnnormalized = mutableMapOf<String, Double>()

            for (cell in allCells) {
                val apPmfForCell = pmfsByBssid[bssidPrime]?.find { it.cell == cell }
                val likelihood = getLikelihood(bssidPrime, cell, observedRssi, apPmfForCell)

                val effectiveLikelihood = if (likelihood == 0.0) 10.0e-6 else likelihood

                nextPosteriorsUnnormalized[cell] = effectiveLikelihood * (currentPriors[cell] ?: (1.0/numCells))
            }
            currentPriors = normalizeProbabilities(nextPosteriorsUnnormalized).toMutableMap()
            Log.d(TAG, "Serial - After AP $bssidPrime (RSSI $observedRssi): Priors/Posteriors: $currentPriors")

            // --- Cutoff Logic ---
            val highestProb = currentPriors.values.maxOrNull() ?: 0.0
            if (highestProb >= cutoffProbability) {
                Log.d(TAG, "Serial - Cutoff reached! Highest probability ($highestProb) >= $cutoffProbability. Stopping.")
                return currentPriors // Return early
            }
        }
        return currentPriors
    }

    private fun normalizeProbabilities(probs: Map<String, Double>): Map<String, Double> {
        val sum = probs.values.sum()
        return if (sum == 0.0 || probs.isEmpty()) {
            if (probs.isNotEmpty()) {
                val equalProb = 1.0 / probs.size
                probs.mapValues { equalProb }
            } else {
                emptyMap()
            }
        } else {
            probs.mapValues { it.value / sum }
        }
    }
}