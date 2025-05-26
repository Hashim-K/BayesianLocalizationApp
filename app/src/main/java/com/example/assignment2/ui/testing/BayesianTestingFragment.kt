package com.example.assignment2.ui.testing // Or your preferred package

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.BayesianSettings
import com.example.assignment2.data.model.MeasurementType
import com.example.assignment2.data.model.ParallelSelectionMethod
import com.example.assignment2.util.BayesianPredictor
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BayesianTestingFragment : Fragment() {

    private val TAG = "BayesianTestingFragment"
    private val PREFS_NAME = "BayesianPrefs" // Must match HomeFragment
    private val KEY_BAYESIAN_SETTINGS = "bayesianSettings" // Must match HomeFragment
    private val DEFAULT_RSSI_FOR_TESTING = -100 // Consistent with ApMeasurement default


    // UI Elements
    private lateinit var textTestingStatus: TextView
    private lateinit var buttonRunBayesianTesting: Button
    private lateinit var buttonClearTestingResults: Button
    private lateinit var textOverallAccuracy: TextView
    private lateinit var textCorrectPredictions: TextView
    private lateinit var textTotalPredictions: TextView
    private lateinit var textIndividualResults: TextView
    private lateinit var fabTestingSettings: FloatingActionButton

    // DAOs (needed for BayesianPredictor constructor)
    private val appDb by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    private val measurementTimeDao by lazy { appDb.measurementTimeDao() }
    private val apMeasurementDao by lazy { appDb.apMeasurementDao() }
    private val apPmfDao by lazy { appDb.apPmfDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }

    // Bayesian Predictor
    private lateinit var bayesianPredictor: BayesianPredictor

    // State
    private var testingRunning: Boolean = false
    private lateinit var currentBayesianSettings: BayesianSettings // Non-nullable after onCreate
    private val displayCells = Array(10) { i -> "C${i + 1}" }.toList()
    private lateinit var sharedPreferences: SharedPreferences // To access shared settings


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBayesianSettings() // Load settings first
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_bayesian_testing, container, false)

        // Initialize UI Elements
        textTestingStatus = root.findViewById(R.id.text_testing_status)
        buttonRunBayesianTesting = root.findViewById(R.id.button_run_bayesian_testing)
        buttonClearTestingResults = root.findViewById(R.id.button_clear_testing_results)
        textOverallAccuracy = root.findViewById(R.id.text_overall_accuracy)
        textCorrectPredictions = root.findViewById(R.id.text_correct_predictions)
        textTotalPredictions = root.findViewById(R.id.text_total_predictions)
        textIndividualResults = root.findViewById(R.id.text_individual_results)
        fabTestingSettings = root.findViewById(R.id.fab_testing_settings)

        // Initialize BayesianPredictor
        bayesianPredictor = BayesianPredictor(apPmfDao, knownApPrimeDao)

        setupListeners()
        updateUIState(0, 0) // Initialize stats display

        return root
    }

    override fun onResume() {
        super.onResume()
        loadBayesianSettings() // Reload settings on resume (in case they changed via HomeFragment)
        textTestingStatus.text = "Ready to run tests. Using ${currentBayesianSettings.mode} mode (Bin Width: ${currentBayesianSettings.pmfBinWidth})."
    }

    private fun setupListeners() {
        buttonRunBayesianTesting.setOnClickListener {
            if (!testingRunning) {
                runBayesianTesting()
            } else {
                Toast.makeText(context, "Testing is already running.", Toast.LENGTH_SHORT).show()
            }
        }

        buttonClearTestingResults.setOnClickListener {
            clearResultsDisplay()
            Toast.makeText(context, "Results cleared.", Toast.LENGTH_SHORT).show()
        }

        fabTestingSettings.setOnClickListener {
            showBayesianSettingsDialog()
        }
    }

    // Load settings from SharedPreferences
    private fun loadBayesianSettings() {
        val settingsJson = sharedPreferences.getString(KEY_BAYESIAN_SETTINGS, null)
        currentBayesianSettings = if (settingsJson != null) {
            try {
                Gson().fromJson(settingsJson, BayesianSettings::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Bayesian settings from preferences, using defaults.", e)
                BayesianSettings() // Fallback to defaults
            }
        } else {
            BayesianSettings() // Defaults if nothing saved
        }
        Log.d(TAG, "Loaded Bayesian settings for testing: $currentBayesianSettings")
    }

    // Save settings to SharedPreferences
    private fun saveBayesianSettings(settings: BayesianSettings) {
        currentBayesianSettings = settings
        val settingsJson = Gson().toJson(settings)
        sharedPreferences.edit().putString(KEY_BAYESIAN_SETTINGS, settingsJson).apply()
        Log.d(TAG, "Saved settings: $currentBayesianSettings")
        Toast.makeText(context, "Bayesian settings saved!", Toast.LENGTH_SHORT).show()
    }

    // Dialog for settings (copied and adapted from HomeFragment)
    private fun showBayesianSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_bayesian_settings, null)
        val switchMode = dialogView.findViewById<SwitchMaterial>(R.id.switch_bayesian_mode_settings)
        val layoutParallelOptions = dialogView.findViewById<View>(R.id.layout_parallel_options_settings)
        val spinnerSelectionMethod = dialogView.findViewById<Spinner>(R.id.spinner_parallel_selection_method_settings)
        val seekBarPmfBinWidth = dialogView.findViewById<SeekBar>(R.id.seekbar_pmf_bin_width_settings)
        val labelPmfBinWidth = dialogView.findViewById<TextView>(R.id.label_pmf_bin_width_settings)
        // New UI elements for serial cutoff
        val seekBarSerialCutoff = dialogView.findViewById<SeekBar>(R.id.seekbar_serial_cutoff_probability)
        val labelSerialCutoff = dialogView.findViewById<TextView>(R.id.label_serial_cutoff_probability)


        val currentSettings = currentBayesianSettings // Guaranteed non-null

        // Initialize mode switch
        switchMode.isChecked = currentSettings.mode == BayesianMode.PARALLEL
        switchMode.text = if (switchMode.isChecked) "Mode: Parallel" else "Mode: Serial"
        layoutParallelOptions.visibility = if (switchMode.isChecked) View.VISIBLE else View.GONE

        // Initialize PMF Bin Width slider
        seekBarPmfBinWidth.progress = currentSettings.pmfBinWidth
        labelPmfBinWidth.text = "PMF Bin Width to Use: ${currentSettings.pmfBinWidth}"

        // Initialize Serial Cutoff Probability slider
        seekBarSerialCutoff.progress = (currentSettings.serialCutoffProbability * 100).roundToInt()
        labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", currentSettings.serialCutoffProbability)}"


        // Parallel Selection Method Spinner
        ArrayAdapter.createFromResource(
            requireContext(), R.array.parallel_selection_methods, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerSelectionMethod.adapter = adapter
            spinnerSelectionMethod.setSelection(
                if (currentSettings.selectionMethod == ParallelSelectionMethod.HIGHEST_PROBABILITY) 0 else 0
            )
        }

        // Listeners for dialog elements
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            switchMode.text = if (isChecked) "Mode: Parallel" else "Mode: Serial"
            layoutParallelOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        seekBarPmfBinWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                labelPmfBinWidth.text = "PMF Bin Width to Use: ${progress.coerceAtLeast(1)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarSerialCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.toFloat() / 100f
                labelSerialCutoff.text = "Serial Cutoff Probability: ${String.format(Locale.US, "%.2f", value)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })


        AlertDialog.Builder(requireContext())
            .setTitle("Bayesian Prediction Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newMode = if (switchMode.isChecked) BayesianMode.PARALLEL else BayesianMode.SERIAL
                val newSelectionMethod = if (spinnerSelectionMethod.selectedItemPosition == 0) ParallelSelectionMethod.HIGHEST_PROBABILITY else ParallelSelectionMethod.HIGHEST_PROBABILITY
                val newPmfBinWidth = seekBarPmfBinWidth.progress.coerceAtLeast(1)
                val newSerialCutoff = (seekBarSerialCutoff.progress.toFloat() / 100f).toDouble()  // Convert to Double

                saveBayesianSettings(
                    BayesianSettings(
                        mode = newMode,
                        selectionMethod = newSelectionMethod,
                        pmfBinWidth = newPmfBinWidth,
                        serialCutoffProbability = newSerialCutoff// Save new cutoff
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runBayesianTesting() {
        if (currentBayesianSettings == null) {
            Toast.makeText(context, "Bayesian settings not loaded. Try restarting app or setting in Home.", Toast.LENGTH_LONG).show()
            return
        }

        testingRunning = true
        buttonRunBayesianTesting.isEnabled = false
        buttonClearTestingResults.isEnabled = false
        textTestingStatus.text = "Loading testing data..."
        clearResultsDisplay()

        val settings = currentBayesianSettings // Use the loaded settings

        lifecycleScope.launch {
            try {
                // 1. Fetch all TESTING MeasurementTime entries
                val testingScanEvents = withContext(Dispatchers.IO) {
                    measurementTimeDao.getAll().filter { it.measurementType == MeasurementType.TESTING }
                }

                if (testingScanEvents.isEmpty()) {
                    textTestingStatus.text = "No TESTING data found. Record WiFi scans as 'Testing' first."
                    Toast.makeText(context, "No testing data available.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                textTestingStatus.text = "Processing ${testingScanEvents.size} testing samples..."

                // 2. Fetch all ApMeasurements once, then group by timestampId
                val allApMeasurements = withContext(Dispatchers.IO) { apMeasurementDao.getAllRawMeasurementsOrdered() }
                val apMeasurementsByTimestampId = allApMeasurements.groupBy { it.timestampId }

                var correctPredictions = 0
                val individualResultsBuilder = StringBuilder()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)


                // 5. Iterate through each testing scan event
                for (testScanEvent in testingScanEvents) {
                    val actualCell = testScanEvent.cell
                    val timestampMillis = testScanEvent.timestampMillis
                    val liveScanApMeasurements = apMeasurementsByTimestampId[testScanEvent.timestampId] ?: emptyList()

                    val liveRssiMap = mutableMapOf<String, Int>() // bssidPrime -> Strongest RSSI
                    liveScanApMeasurements.forEach { apm ->
                        liveRssiMap[apm.bssidPrime] = apm.rssi // Use bssidPrime directly
                    }

                    if (liveRssiMap.isEmpty()) {
                        individualResultsBuilder.append(
                            "Time: ${dateFormat.format(Date(timestampMillis))}, True: $actualCell, Pred: N/A, Prob: N/A, Status: NO_APS_DETECTED\n"
                        )
                        continue
                    }

                    // Perform Bayesian prediction using the predictor
                    val posteriorProbabilities: Map<String, Double> = bayesianPredictor.predict(
                        liveRssiMap,
                        settings,
                        displayCells
                    )

                    if (posteriorProbabilities.isEmpty()) {
                        individualResultsBuilder.append(
                            "Time: ${dateFormat.format(Date(timestampMillis))}, " +
                                    "True: $actualCell, " +
                                    "Pred: N/A, Prob: N/A, Status: NO_PREDICTION_POSSIBLE\n"
                        )
                        continue
                    }

                    val predictedCell = posteriorProbabilities.maxByOrNull { it.value }?.key
                    val predictionConfidence = posteriorProbabilities[predictedCell] ?: 0.0

                    val status = if (predictedCell == actualCell) {
                        correctPredictions++
                        "CORRECT"
                    } else {
                        "INCORRECT"
                    }

                    individualResultsBuilder.append(
                        "Time: ${dateFormat.format(Date(timestampMillis))}, " +
                                "True: $actualCell, " +
                                "Pred: ${predictedCell ?: "N/A"}, " +
                                "Prob: ${String.format(Locale.US, "%.3f", predictionConfidence)}, " +
                                "Status: $status\n"
                    )
                } // End of testingScanEvents loop

                val totalPredictions = testingScanEvents.size
                updateUIState(correctPredictions, totalPredictions, individualResultsBuilder.toString())

            } catch (e: Exception) {
                Log.e(TAG, "Error during Bayesian testing evaluation", e)
                Toast.makeText(context, "Error during testing: ${e.message}", Toast.LENGTH_LONG).show()
                textTestingStatus.text = "Testing failed: ${e.localizedMessage}"
            } finally {
                testingRunning = false
                buttonRunBayesianTesting.isEnabled = true
                buttonClearTestingResults.isEnabled = true
                if (!isAdded) return@launch
                textTestingStatus.text = "Testing complete. Last run at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}."
            }
        }
    }

    private fun updateUIState(correct: Int, total: Int, individualResults: String = "") {
        val accuracy = if (total > 0) (correct.toDouble() / total * 100) else 0.0
        textOverallAccuracy.text = String.format(Locale.US, "%.2f%%", accuracy)
        textCorrectPredictions.text = "Correct: $correct"
        textTotalPredictions.text = "Total: $total"
        textIndividualResults.text = individualResults
    }

    private fun clearResultsDisplay() {
        textOverallAccuracy.text = "N/A"
        textCorrectPredictions.text = "Correct: 0"
        textTotalPredictions.text = "Total: 0"
        textIndividualResults.text = ""
    }
}