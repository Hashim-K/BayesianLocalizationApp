package com.example.assignment2.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.model.BayesianSettings
import com.example.assignment2.data.model.BayesianMode
import com.example.assignment2.data.model.ParallelSelectionMethod
import com.example.assignment2.util.BayesianPredictor
import com.example.assignment2.util.BssidUtil
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private val TAG = "HomeFragment"
    private val PREFS_NAME = "BayesianPrefs"
    private val KEY_BAYESIAN_SETTINGS = "bayesianSettings"
    private val DEFAULT_RSSI_FOR_SCAN = -100 // Consistent with ApMeasurement default

    // UI Elements
    private lateinit var buttonGuessLocationHome: Button
    private lateinit var textCurrentGuessedCellHome: TextView
    private lateinit var textLocationStatusHome: TextView
    private lateinit var fabBayesianSettings: FloatingActionButton
    private lateinit var cellViews: List<TextView>

    // System Services
    private lateinit var wifiManager: WifiManager
    private var isWifiReceiverRegistered = false

    // Database & PMF DAOs (needed for BayesianPredictor constructor)
    private val appDb by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    private val apPmfDao by lazy { appDb.apPmfDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }

    // Bayesian Predictor
    private lateinit var bayesianPredictor: BayesianPredictor

    // Bayesian Settings (loaded from SharedPreferences)
    private lateinit var currentBayesianSettings: BayesianSettings
    private val gson = Gson()
    private lateinit var sharedPreferences: SharedPreferences

    // State for guessing process
    private var isScanningForGuess = false
    private val displayCells = Array(10) { i -> "C${i + 1}" }.toList()

    // Colors
    private var activeCellColor: Int = 0
    private var inactiveCellColor: Int = 0

    // Permission Launcher
    private val requestLocationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startWifiScanForGuess()
            } else {
                Toast.makeText(context, "Location Permission Denied for WiFi Scan", Toast.LENGTH_SHORT).show()
                isScanningForGuess = false
                updateGuessButtonState()
            }
        }

    // WiFi Scan Receiver
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE])
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isAdded || context == null) return
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "Scan results for guess available: $success")
                isScanningForGuess = false
                updateGuessButtonState()
                if (success) {
                    try {
                        performBayesianGuess(wifiManager.scanResults)
                    } catch (se: SecurityException) {
                        Log.e(TAG, "SecurityException processing scan results", se)
                        textLocationStatusHome.text = "Permission error for results."
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing scan results", e)
                        textLocationStatusHome.text = "Error processing results."
                    }
                } else {
                    textLocationStatusHome.text = "WiFi Scan Failed."
                    highlightGuessedCell(null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBayesianSettings() // Load settings first
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        Log.d(TAG, "onCreateView")

        initServices() // Initialize services (including wifiManager) FIRST
        bayesianPredictor = BayesianPredictor(apPmfDao, knownApPrimeDao) // Initialize BayesianPredictor

        // Initialize colors from theme attributes
        val typedValue = TypedValue()
        val theme = requireContext().theme
        theme.resolveAttribute(R.attr.homeCellActiveBackground, typedValue, true)
        activeCellColor = typedValue.data
        theme.resolveAttribute(R.attr.homeCellInactiveBackground, typedValue, true)
        inactiveCellColor = typedValue.data

        // Initialize UI Elements
        textCurrentGuessedCellHome = root.findViewById(R.id.text_current_highlighted_cell_home)
        buttonGuessLocationHome = root.findViewById(R.id.button_guess_location_home)
        textLocationStatusHome = root.findViewById(R.id.text_location_status_home)
        fabBayesianSettings = root.findViewById(R.id.fab_bayesian_settings)

        // Initialize cellViews list safely
        cellViews = displayCells.mapNotNull { label ->
            val resIdName = "cell_${label.lowercase()}_home"
            val resId = resources.getIdentifier(resIdName, "id", requireContext().packageName)
            if (resId != 0) {
                root.findViewById<TextView>(resId)
            } else {
                Log.e(TAG, "Could not find view ID for $resIdName")
                null
            }
        }


        // Setup Listeners
        buttonGuessLocationHome.setOnClickListener {
            Log.d(TAG, "Guess Location button clicked")
            if (!isScanningForGuess) {
                checkPermissionsAndStartScan()
            }
        }
        fabBayesianSettings.setOnClickListener {
            showBayesianSettingsDialog()
        }

        updateGuessButtonState() // Update button state initially
        highlightGuessedCell(null) // Initially no cell highlighted
        textLocationStatusHome.text = "Ready to guess location."
        return root
    }

    override fun onStart() {
        super.onStart()
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            requireContext().registerReceiver(wifiScanReceiver, filter)
            isWifiReceiverRegistered = true
        } catch (e: Exception) { Log.e(TAG, "Error registering WiFi receiver", e) }
    }

    override fun onStop() {
        super.onStop()
        if (isWifiReceiverRegistered) {
            try { requireContext().unregisterReceiver(wifiScanReceiver) }
            catch (e: Exception) { Log.w(TAG, "Error unregistering WiFi receiver", e) }
            isWifiReceiverRegistered = false
        }
    }

    // Initialize system services like WifiManager
    private fun initServices() {
        // SensorManager initialization for accelerometer if needed, e.g.:
        // sensorManager = requireActivity().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d(TAG, "WifiManager initialized.")
    }

    private fun loadBayesianSettings() {
        val settingsJson = sharedPreferences.getString(KEY_BAYESIAN_SETTINGS, null)
        currentBayesianSettings = if (settingsJson != null) {
            try {
                gson.fromJson(settingsJson, BayesianSettings::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Bayesian settings, using defaults.", e)
                BayesianSettings() // Fallback to defaults
            }
        } else {
            BayesianSettings() // Defaults if nothing saved
        }
        Log.d(TAG, "Loaded settings: $currentBayesianSettings")
    }

    private fun saveBayesianSettings(settings: BayesianSettings) {
        currentBayesianSettings = settings
        val settingsJson = gson.toJson(settings)
        sharedPreferences.edit().putString(KEY_BAYESIAN_SETTINGS, settingsJson).apply()
        Log.d(TAG, "Saved settings: $currentBayesianSettings")
        Toast.makeText(context, "Bayesian settings saved!", Toast.LENGTH_SHORT).show()
    }

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
                val newSerialCutoff = (seekBarSerialCutoff.progress.toFloat() / 100f).toDouble() // Convert to Double

                saveBayesianSettings(
                    BayesianSettings(
                        mode = newMode,
                        selectionMethod = newSelectionMethod,
                        pmfBinWidth = newPmfBinWidth,
                        serialCutoffProbability = newSerialCutoff // Save new cutoff
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Check permissions and start WiFi scan
    private fun checkPermissionsAndStartScan() {
        val context = context ?: return
        val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val nearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.NEARBY_WIFI_DEVICES else null
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, locationPermission) == PackageManager.PERMISSION_GRANTED
        val hasNearbyDevicesPermission = nearbyDevicesPermission?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true

        when {
            hasLocationPermission && hasNearbyDevicesPermission -> startWifiScanForGuess()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasNearbyDevicesPermission -> requestLocationPermissionLauncher.launch(nearbyDevicesPermission!!)
            !hasLocationPermission -> requestLocationPermissionLauncher.launch(locationPermission)
            else -> startWifiScanForGuess()
        }
    }

    @RequiresPermission(Manifest.permission.CHANGE_WIFI_STATE)
    private fun startWifiScanForGuess() {
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(requireContext(), "Please enable WiFi", Toast.LENGTH_SHORT).show()
            return
        }
        isScanningForGuess = true
        updateGuessButtonState()
        textLocationStatusHome.text = "Scanning WiFi for location..."
        if (!wifiManager.startScan()) {
            Log.e(TAG, "wifiManager.startScan() returned false.")
            Toast.makeText(requireContext(), "Failed to start WiFi scan.", Toast.LENGTH_SHORT).show()
            textLocationStatusHome.text = "Failed to start scan."
            isScanningForGuess = false
            updateGuessButtonState()
        }
    }

    private fun updateGuessButtonState() {
        buttonGuessLocationHome.isEnabled = !isScanningForGuess
    }

    private fun highlightGuessedCell(guessedCellLabel: String?) {
        if (!isAdded) return // Ensure fragment is still attached
        textCurrentGuessedCellHome.text = guessedCellLabel ?: "---"
        cellViews.forEach { cellView ->
            val cellText = cellView.text.toString()
            if (cellText == guessedCellLabel) {
                cellView.setBackgroundColor(activeCellColor)
            } else {
                cellView.setBackgroundColor(inactiveCellColor)
            }
        }
    }

    private fun performBayesianGuess(scanResults: List<ScanResult>?) {
        if (scanResults.isNullOrEmpty()) {
            textLocationStatusHome.text = "No WiFi APs found in scan."
            highlightGuessedCell(null)
            return
        }
        textLocationStatusHome.text = "Calculating guess..."
        Log.d(TAG, "Performing Bayesian guess with ${scanResults.size} scan results. Settings: $currentBayesianSettings")

        lifecycleScope.launch {
            val liveRssiMap = mutableMapOf<String, Int>() // BSSID_Prime -> Strongest RSSI
            scanResults.forEach { sr ->
                BssidUtil.calculateBssidPrime(sr.BSSID)?.let { prime ->
                    liveRssiMap[prime] = max(liveRssiMap.getOrDefault(prime, DEFAULT_RSSI_FOR_SCAN), sr.level)
                }
            }

            if (liveRssiMap.isEmpty()) {
                textLocationStatusHome.text = "No usable APs in scan for prediction."
                highlightGuessedCell(null)
                return@launch
            }

            // Use the BayesianPredictor instance to predict
            val posteriorProbabilities: Map<String, Double> = bayesianPredictor.predict(
                liveRssiMap,
                currentBayesianSettings,
                displayCells.toList() // Pass the list of all possible cells
            )

            if (posteriorProbabilities.isEmpty()) {
                textLocationStatusHome.text = "Could not calculate probabilities (check PMFs/data)."
                highlightGuessedCell(null)
                return@launch
            }

            // Determine guessed cell based on highest probability
            val guessedCell = posteriorProbabilities.maxByOrNull { it.value }?.key

            val highestProb = posteriorProbabilities[guessedCell] ?: 0.0
            textLocationStatusHome.text = "Guessed: ${guessedCell ?: "Unknown"} (Prob: ${String.format(
                Locale.US, "%.3f", highestProb)})"
            highlightGuessedCell(guessedCell)
            Log.d(TAG, "Posterior probabilities: $posteriorProbabilities")
            Log.d(TAG, "Final Guess: $guessedCell")
        }
    }
}