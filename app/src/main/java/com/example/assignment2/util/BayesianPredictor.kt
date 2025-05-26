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
    // Using a very small constant for cases where an AP has NO PMF data AT ALL (ApPmf object is null/empty).
    // This is distinct from a bin having 0 counts within an existing PMF.
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
        }.groupBy { it.bssidPrime } // Group by bssidPrime for easy lookup

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
                allFixedApBssidsInModel
            )
        }
    }

    /**
     * Calculates the likelihood P(Observed RSSI | AP, Cell, BinWidth) from an ApPmf.
     * This version applies NO smoothing.
     *
     * @param bssidPrime The BSSID Prime of the AP being evaluated.
     * @param cell The cell being evaluated.
     * @param observedRssi The RSSI value observed for a specific AP.
     * @param apPmf The ApPmf object for that AP in a given cell.
     * @return The calculated likelihood (a value between 0.0 and 1.0).
     *         Returns LIKELIHOOD_FOR_MISSING_AP_MODEL if `apPmf` is null or its data is empty.
     */
    private fun getLikelihood(
        bssidPrime: String, // Added for logging context
        cell: String,       // Added for logging context
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
            Log.w(TAG, "Total count in PMF is zero for AP $bssidPrime in Cell $cell. Likelihood is 0.0.")
            return 0.0
        }

        // Determine which bin the observedRssi falls into
        val relevantBinStart = apPmf.getBinStartForRssi(observedRssi)
        val countInBin = binsData[relevantBinStart] ?: 0

        // Direct frequency: count_in_bin / total_count
        val likelihood = countInBin.toDouble() / totalCount.toDouble()

        if (likelihood == 0.0) {
            Log.d(TAG, "Likelihood is 0.0 for AP $bssidPrime (RSSI $observedRssi, Bin $relevantBinStart) in Cell $cell. (Bin had 0 counts).")
        }
        return likelihood
    }

    /**
     * Performs Bayesian prediction in parallel mode (Naive Bayes).
     * Each cell's posterior is calculated independently by multiplying priors by
     * the likelihoods of all APs given that cell.
     * If a likelihood for an AP/Cell is 0.0, it is 'disregarded' by multiplying by 1.0.
     *
     * @param liveRssiMap Map of detected BSSID Prime to RSSI from the current scan.
     * @param pmfsByBssid Map of BSSID Prime to a list of ApPmf objects.
     * @param allCells List of all possible cell labels.
     * @param allFixedApBssidsInModel Set of all fixed BSSID primes in the historical model.
     * @return Map of Cell labels to their posterior probabilities.
     */
    private fun calculateParallelBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>
    ): Map<String, Double> {
        val posterior = mutableMapOf<String, Double>()
        val numCells = allCells.size
        val initialPrior = 1.0 / numCells

        // Loop through each possible cell
        for (cell in allCells) {
            var cellLikelihoodProduct = 1.0

            // Loop through each fixed AP in the model (to include unobserved APs)
            for (bssidPrimeInModel in allFixedApBssidsInModel) {
                val observedRssi = liveRssiMap.getOrDefault(bssidPrimeInModel, DEFAULT_RSSI)
                val apPmfForCell = pmfsByBssid[bssidPrimeInModel]?.find { it.cell == cell }

                val likelihood = getLikelihood(bssidPrimeInModel, cell, observedRssi, apPmfForCell)

                // Implement "disregard" logic: if likelihood is 0.0, multiply by 1.0 instead.
                cellLikelihoodProduct *= if (likelihood == 0.0) 1.0 else likelihood
            }
            posterior[cell] = cellLikelihoodProduct * initialPrior
        }
        return normalizeProbabilities(posterior)
    }

    /**
     * Performs Bayesian prediction in serial mode (Iterative Bayes).
     * Iteratively updates posteriors, with the posterior from one AP becoming the prior for the next.
     * If a likelihood for an AP/Cell is 0.0, it is 'disregarded' by multiplying by 1.0.
     *
     * @param liveRssiMap Map of detected BSSID Prime to RSSI from the current scan.
     * @param pmfsByBssid Map of BSSID Prime to a list of ApPmf objects.
     * @param allCells List of all possible cell labels.
     * @param allFixedApBssidsInModel Set of all fixed BSSID primes in the historical model.
     * @return Map of Cell labels to their posterior probabilities.
     */
    private fun calculateSerialBayesian(
        liveRssiMap: Map<String, Int>,
        pmfsByBssid: Map<String, List<ApPmf>>,
        allCells: List<String>,
        allFixedApBssidsInModel: Set<String>
    ): Map<String, Double> {
        val numCells = allCells.size
        var currentPriors = allCells.associateWith { 1.0 / numCells }.toMutableMap()

        // Loop through fixed APs, sorted by their RSSI in the live scan (strongest first).
        // This ensures all fixed APs in the model are processed, even if not seen in current scan.
        val sortedFixedApBssidsToProcess = allFixedApBssidsInModel.toList().sortedByDescending { liveRssiMap.getOrDefault(it, DEFAULT_RSSI) }

        Log.d(TAG, "Serial Bayesian AP processing order:")
        sortedFixedApBssidsToProcess.forEachIndexed { index, bssidPrime ->
            val rssi = liveRssiMap.getOrDefault(bssidPrime, DEFAULT_RSSI)
            Log.d(TAG, "  ${index + 1}. BSSID: $bssidPrime, RSSI: $rssi dBm")
        }

        // Outer loop: Iterate through each AP
        for (bssidPrime in sortedFixedApBssidsToProcess) { // Renamed from allFixedApBssidsToProcess to make sure I am iterating only in this specific case over fixed ap.
            val observedRssi = liveRssiMap.getOrDefault(bssidPrime, DEFAULT_RSSI)
            val nextPosteriorsUnnormalized = mutableMapOf<String, Double>()

            // Inner loop: Iterate through each cell
            for (cell in allCells) {
                val apPmfForCell = pmfsByBssid[bssidPrime]?.find { it.cell == cell }
                val likelihood = getLikelihood(bssidPrime, cell, observedRssi, apPmfForCell)

                // Implement "disregard" logic: if likelihood is 0.0, multiply by 1.0 instead.
                val effectiveLikelihood = if (likelihood == 0.0) 1.0 else likelihood

                nextPosteriorsUnnormalized[cell] = effectiveLikelihood * (currentPriors[cell] ?: (1.0/numCells))
            }
            // Normalize after processing each AP to get new priors for the next iteration
            currentPriors = normalizeProbabilities(nextPosteriorsUnnormalized).toMutableMap()
            Log.d(TAG, "Serial - After AP $bssidPrime (RSSI $observedRssi): Priors/Posteriors: $currentPriors")
        }
        return currentPriors // Already normalized after the last step
    }

    /**
     * Normalizes a map of probabilities so their sum is 1.0.
     */
    private fun normalizeProbabilities(probs: Map<String, Double>): Map<String, Double> {
        val sum = probs.values.sum()
        return if (sum == 0.0 || probs.isEmpty()) {
            // If sum is 0 (all values are 0), or no entries, distribute probability equally.
            // This prevents an empty posterior and ensures a guess can always be made.
            if (probs.isNotEmpty()) {
                val equalProb = 1.0 / probs.size
                probs.mapValues { equalProb }
            } else {
                emptyMap() // Return empty map if input was empty
            }
        } else {
            probs.mapValues { it.value / sum }
        }
    }
}