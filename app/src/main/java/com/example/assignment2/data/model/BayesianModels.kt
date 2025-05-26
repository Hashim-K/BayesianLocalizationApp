package com.example.assignment2.data.model

enum class BayesianMode {
    SERIAL, PARALLEL
}

enum class ParallelSelectionMethod {
    HIGHEST_PROBABILITY
}

data class BayesianSettings(
    val mode: BayesianMode = BayesianMode.PARALLEL,
    val selectionMethod: ParallelSelectionMethod = ParallelSelectionMethod.HIGHEST_PROBABILITY,
    val pmfBinWidth: Int = 1,
    val serialCutoffProbability: Double = 0.8 // New field, default 0.8
)