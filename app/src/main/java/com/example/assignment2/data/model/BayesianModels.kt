package com.example.assignment2.data.model

enum class BayesianMode {
    SERIAL, PARALLEL
}

enum class ParallelSelectionMethod {
    HIGHEST_PROBABILITY // For now, only this. Can add MODE later.
}

data class BayesianSettings(
    val mode: BayesianMode = BayesianMode.PARALLEL, // Default mode
    val selectionMethod: ParallelSelectionMethod = ParallelSelectionMethod.HIGHEST_PROBABILITY,
    val pmfBinWidth: Int = 5 // Default bin width to use for PMF lookup
)