package com.example.assignment2.ui.wifi_scan

// Android/System Imports
import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

// AndroidX/Jetpack Imports
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

// Your Project Imports
import com.example.assignment2.R
import com.example.assignment2.data.db.ApMeasurement
import com.example.assignment2.data.db.AppDatabase
// import com.example.assignment2.data.db.KnownAp // REMOVED
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.data.db.MeasurementTime
import com.example.assignment2.data.model.ApType
import com.example.assignment2.data.model.MeasurementType
import com.example.assignment2.util.BssidUtil
import com.google.android.material.switchmaterial.SwitchMaterial


// Coroutines Imports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Java Imports
import java.lang.reflect.Method

class WifiScanFragment : Fragment() {

    private val DEFAULT_RSSI: Int = -100
    private val TAG = "WifiScanFragment"

    // UI Elements
    private lateinit var wifiResultsTextView: TextView
    private lateinit var scanButton: Button
    private lateinit var batchModeButton: Button
    private lateinit var sampleCountTextView: TextView
    private lateinit var measurementTypeSwitch: SwitchMaterial

    private lateinit var cellButtons: List<Button>
    private lateinit var buttonC1: Button
    private lateinit var buttonC2: Button
    private lateinit var buttonC3: Button
    private lateinit var buttonC4: Button
    private lateinit var buttonC5: Button
    private lateinit var buttonC6: Button
    private lateinit var buttonC7: Button
    private lateinit var buttonC8: Button
    private lateinit var buttonC9: Button
    private lateinit var buttonC10: Button

    // WiFi Management
    private lateinit var wifiManager: WifiManager
    private var isReceiverRegistered = false

    // State
    private var activeCellLabel: String? = null
    private var isBatchModeActive = false
    // Batch mode specific state
    private var targetSampleCountForBatchType = 0
    private var currentSampleCountForBatchType = 0 // Tracks count for the active batch type
    private var activeBatchMeasurementType: MeasurementType? = null


    // Database instances
    private val appDb: AppDatabase by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    // private val knownApDao by lazy { appDb.knownApDao() } // REMOVED
    private val measurementDao by lazy { appDb.apMeasurementDao() }
    private val measurementTimeDao by lazy { appDb.measurementTimeDao() }
    private val ouiDao by lazy { appDb.ouiManufacturerDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }


    // BroadcastReceiver
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE])
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                if (!isAdded || context == null) {
                    Log.w(TAG, "Receiver called but fragment not attached.")
                    if (isBatchModeActive) stopBatchMode("Fragment detached during scan.")
                    return
                }
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "Scan results available: $success")
                if (success) {
                    displayScanResults()
                } else {
                    wifiResultsTextView.text = "WiFi Scan Failed (No new results)"
                    Toast.makeText(context, "WiFi Scan Failed", Toast.LENGTH_SHORT).show()
                    if (isBatchModeActive) stopBatchMode("Scan failed.")
                }
            }
        }
    }

    // Permission Launcher
    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "Permission granted by user.")
                startWifiScanIfCellActive()
            } else {
                Log.w(TAG, "Permission denied by user.")
                Toast.makeText(requireContext(), "Location permission denied. Cannot scan for WiFi.", Toast.LENGTH_LONG).show()
                wifiResultsTextView.text = "Location permission required for scanning."
                if (isBatchModeActive) stopBatchMode("Permission denied.")
            }
        }

    // --- Fragment Lifecycle Methods ---
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d(TAG, "onCreateView")
        val root = inflater.inflate(R.layout.fragment_wifi_scan, container, false)

        wifiResultsTextView = root.findViewById(R.id.text_wifi_results)
        scanButton = root.findViewById(R.id.button_scan_wifi)
        batchModeButton = root.findViewById(R.id.button_batch_mode)
        sampleCountTextView = root.findViewById(R.id.text_sample_count)
        measurementTypeSwitch = root.findViewById(R.id.switch_measurement_type)

        buttonC1 = root.findViewById(R.id.button_cell_c1)
        buttonC2 = root.findViewById(R.id.button_cell_c2)
        buttonC3 = root.findViewById(R.id.button_cell_c3)
        buttonC4 = root.findViewById(R.id.button_cell_c4)
        buttonC5 = root.findViewById(R.id.button_cell_c5)
        buttonC6 = root.findViewById(R.id.button_cell_c6)
        buttonC7 = root.findViewById(R.id.button_cell_c7)
        buttonC8 = root.findViewById(R.id.button_cell_c8)
        buttonC9 = root.findViewById(R.id.button_cell_c9)
        buttonC10 = root.findViewById(R.id.button_cell_c10)

        cellButtons = listOf(
            buttonC1, buttonC2, buttonC3, buttonC4, buttonC5,
            buttonC6, buttonC7, buttonC8, buttonC9, buttonC10
        )

        try {
            wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WifiManager", e)
            wifiResultsTextView.text = "Could not initialize WiFi service."
            scanButton.isEnabled = false
            batchModeButton.isEnabled = false
            measurementTypeSwitch.isEnabled = false
            cellButtons.forEach { it.isEnabled = false }
            return root
        }

        setupCellButtonListeners()
        setupActionButtons()
        setupMeasurementTypeSwitch()
        updateScanButtonState()
        attemptDisableWifiThrottling(wifiManager)

        return root
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart - Registering receiver")
        try {
            val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            requireContext().registerReceiver(wifiScanReceiver, intentFilter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
            Toast.makeText(requireContext(), "Error setting up WiFi listener", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop - Unregistering receiver")
        if (isBatchModeActive) {
            stopBatchMode("Navigated away")
        }
        if (isReceiverRegistered) {
            try {
                requireContext().unregisterReceiver(wifiScanReceiver)
                isReceiverRegistered = false
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
    }

    // --- UI Interaction Logic ---
    private fun setupCellButtonListeners() {
        cellButtons.forEach { button ->
            button.setOnClickListener {
                if (!isBatchModeActive) {
                    setActiveCell(button)
                } else {
                    Toast.makeText(context, "Cancel batch mode to change cell.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupMeasurementTypeSwitch() {
        measurementTypeSwitch.setOnCheckedChangeListener { _, isChecked ->
            measurementTypeSwitch.text = if (isChecked) "Type: Training" else "Type: Testing"
            // If batch mode is active, changing the switch doesn't affect the current batch.
            // It will only affect the *next* batch if this one is cancelled and a new one started.
        }
        measurementTypeSwitch.text = if (measurementTypeSwitch.isChecked) "Type: Training" else "Type: Testing"
    }


    private fun setActiveCell(selectedButton: Button) {
        val typedValue = TypedValue()
        val theme = requireContext().theme

        theme.resolveAttribute(R.attr.cellButtonActiveBackground, typedValue, true)
        val cellButtonActiveBackground = typedValue.data

        theme.resolveAttribute(R.attr.cellButtonInactiveBackground, typedValue, true)
        val cellButtonInactiveBackground = typedValue.data

        theme.resolveAttribute(R.attr.cellButtonActiveText, typedValue, true)
        val cellButtonActiveText = typedValue.data

        theme.resolveAttribute(R.attr.cellButtonInactiveText, typedValue, true)
        val cellButtonInactiveText = typedValue.data


        activeCellLabel = selectedButton.text.toString()
        Log.d(TAG, "Active cell set to: $activeCellLabel")

        cellButtons.forEach { button ->
            val isActive = button == selectedButton
            button.setBackgroundColor(
                if (isActive) cellButtonActiveBackground
                else cellButtonInactiveBackground
            )
            button.setTextColor(
                if (isActive) cellButtonActiveText
                else cellButtonInactiveText
            )
        }
        updateScanButtonState()
        updateSampleCountDisplay()
    }

    private fun setupActionButtons() {
        scanButton.setOnClickListener {
            Log.d(TAG, "Scan button clicked.")
            checkPermissionsAndScan()
        }
        batchModeButton.setOnClickListener {
            if (isBatchModeActive) {
                stopBatchMode("Cancelled by user")
            } else {
                showBatchInputDialog()
            }
        }
    }


    private fun updateScanButtonState() {
        val cellSelected = activeCellLabel != null
        scanButton.isEnabled = cellSelected && !isBatchModeActive
        batchModeButton.isEnabled = cellSelected // Batch can be started if a cell is selected
        batchModeButton.text = if (isBatchModeActive) "Cancel Batch" else "Batch Mode"

        cellButtons.forEach { it.isEnabled = !isBatchModeActive }
        measurementTypeSwitch.isEnabled = !isBatchModeActive

        if (!cellSelected) {
            wifiResultsTextView.text = "Select a cell to enable scanning."
            sampleCountTextView.visibility = View.INVISIBLE
        } else if (wifiResultsTextView.text.toString() == "Select a cell to enable scanning.") {
            wifiResultsTextView.text = "Cell $activeCellLabel selected. Ready to scan."
        }
    }

    private fun updateSampleCountDisplay() {
        val cell = activeCellLabel ?: return

        sampleCountTextView.visibility = View.VISIBLE
        sampleCountTextView.text = "Loading counts for $cell..."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Use MeasurementTimeDao for counting
                val trainingCount = withContext(Dispatchers.IO) {
                    measurementTimeDao.countScanEventsForCellAndType(cell, MeasurementType.TRAINING)
                }
                val testingCount = withContext(Dispatchers.IO) {
                    measurementTimeDao.countScanEventsForCellAndType(cell, MeasurementType.TESTING)
                }
                val totalCellCount = trainingCount + testingCount

                sampleCountTextView.text = "$cell: Training: $trainingCount, Testing: $testingCount (Total: $totalCellCount)"
                Log.d(TAG, "Sample counts for $cell - Training: $trainingCount, Testing: $testingCount, Total: $totalCellCount")

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching sample counts for $cell", e)
                sampleCountTextView.text = "Count error for $cell"
            }
        }
    }


    private fun getCurrentCellForMeasurement(): String {
        return activeCellLabel ?: run {
            Log.e(TAG, "getCurrentCellForMeasurement called but activeCellLabel is null!")
            "Error"
        }
    }

    // Determines the measurement type for the *next* scan operation
    private fun getMeasurementTypeForCurrentScan(): MeasurementType {
        return if (isBatchModeActive && activeBatchMeasurementType != null) {
            activeBatchMeasurementType!! // Use the stored batch type
        } else {
            // Get from switch if not in batch or batch type not set (shouldn't happen for batch)
            if (measurementTypeSwitch.isChecked) MeasurementType.TRAINING else MeasurementType.TESTING
        }
    }

    // --- Permission and Scan Logic ---
    private fun checkPermissionsAndScan() {
        if (activeCellLabel == null) {
            Toast.makeText(requireContext(), "Please select an active cell first.", Toast.LENGTH_SHORT).show()
            if (isBatchModeActive) stopBatchMode("No cell selected")
            return
        }

        val context = context ?: return
        val locationPermission = Manifest.permission.ACCESS_FINE_LOCATION
        val nearbyDevicesPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.NEARBY_WIFI_DEVICES else null
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, locationPermission) == PackageManager.PERMISSION_GRANTED
        val hasNearbyDevicesPermission = nearbyDevicesPermission?.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ?: true

        when {
            hasLocationPermission && hasNearbyDevicesPermission -> startWifiScanIfCellActive()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasNearbyDevicesPermission -> requestPermissionLauncher.launch(nearbyDevicesPermission!!)
            !hasLocationPermission -> requestPermissionLauncher.launch(locationPermission)
            else -> startWifiScanIfCellActive()
        }
    }

    private fun startWifiScanIfCellActive() {
        if (activeCellLabel == null) {
            Log.w(TAG, "Scan attempt without active cell.")
            Toast.makeText(requireContext(), "Error: No active cell selected.", Toast.LENGTH_SHORT).show()
            if (isBatchModeActive) stopBatchMode("No cell selected")
            return
        }
        startWifiScan()
    }

    @RequiresPermission(Manifest.permission.CHANGE_WIFI_STATE)
    private fun startWifiScan() {
        if (!::wifiManager.isInitialized) {
            wifiResultsTextView.text = "WiFiManager not available."
            Log.e(TAG, "startWifiScan called but wifiManager not initialized.")
            if (isBatchModeActive) stopBatchMode("WiFi Manager Error")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(requireContext(), "Please enable WiFi", Toast.LENGTH_SHORT).show()
            wifiResultsTextView.text = "WiFi is disabled."
            if (isBatchModeActive) stopBatchMode("WiFi disabled")
            return
        }

        val scanType = getMeasurementTypeForCurrentScan()

        if (!isBatchModeActive) {
            wifiResultsTextView.text = "Scanning with cell $activeCellLabel ($scanType)..."
        }
        Log.d(TAG, "Initiating wifiManager.startScan() for cell $activeCellLabel (Type: $scanType, Batch Active: $isBatchModeActive)")

        if (!wifiManager.startScan()) {
            Log.e(TAG, "wifiManager.startScan() returned false.")
            Toast.makeText(requireContext(), "Failed to start WiFi scan (maybe throttled).", Toast.LENGTH_SHORT).show()
            wifiResultsTextView.text = "Failed to start WiFi scan."
            if (isBatchModeActive) stopBatchMode("Scan start failed")
        }
    }

    // --- Displaying Results and Updating Database ---
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_WIFI_STATE])
    private fun displayScanResults() {
        Log.d(TAG, "Attempting to display scan results and update DB.")
        val currentCell = getCurrentCellForMeasurement()
        if (currentCell == "Error") {
            Log.e(TAG, "Cannot process scan results: Active cell is not properly set.")
            wifiResultsTextView.text = "Error: Active cell not set during scan processing."
            if (isBatchModeActive) stopBatchMode("Active cell error")
            return
        }

        val context = context ?: return
        val hasLocationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNearbyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED else true

        if (!hasLocationPermission || !hasNearbyPermission) {
            Log.w(TAG, "displayScanResults called without required permissions.")
            wifiResultsTextView.text = "Permissions missing for scan results."
            if (isBatchModeActive) stopBatchMode("Permission missing")
            return
        }
        if (!::wifiManager.isInitialized) {
            Log.e(TAG, "displayScanResults called but wifiManager not initialized.")
            wifiResultsTextView.text = "WiFi Manager Error."
            if (isBatchModeActive) stopBatchMode("WiFi Manager Error")
            return
        }

        try {
            val scanResults: List<ScanResult>? = wifiManager.scanResults
            val actualTimestampMillis = System.currentTimeMillis()
            val typeForThisScan = getMeasurementTypeForCurrentScan() // Type used for THIS scan

            Log.d(TAG, "Scan results count: ${scanResults?.size ?: 0}, Cell: $currentCell, Type for this scan: $typeForThisScan")

            viewLifecycleOwner.lifecycleScope.launch {
                processScanResultsAndUpdateDb(scanResults, actualTimestampMillis, currentCell, typeForThisScan)
            }

            if (!isBatchModeActive) {
                if (scanResults.isNullOrEmpty()) {
                    wifiResultsTextView.text = "No WiFi networks found for cell $currentCell."
                } else {
                    val resultsBuilder = StringBuilder("Cell: $currentCell ($typeForThisScan) - Scan Results:\n\n")
                    val sortedResults = scanResults.sortedByDescending { it.level }
                    for (scanResult in sortedResults) {
                        val ssid = if (scanResult.SSID.isNullOrEmpty()) "(Hidden Network)" else scanResult.SSID
                        resultsBuilder.append("SSID: ").append(ssid)
                            .append("\n  BSSID: ").append(scanResult.BSSID)
                            .append("\n  RSSI: ").append(scanResult.level).append(" dBm\n\n")
                    }
                    wifiResultsTextView.text = resultsBuilder.toString()
                }
            }

        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException getting scan results", se)
            wifiResultsTextView.text = "Permission error retrieving scan results."
            if (isBatchModeActive) stopBatchMode("Permission error")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan results: ${e.message}", e)
            wifiResultsTextView.text = "Error displaying scan results."
            if (isBatchModeActive) stopBatchMode("Processing error")
        }
    }

    private fun extractOuiFromBssid(bssid: String): String? {
        if (bssid.length >= 8 && bssid.count { it == ':' } >= 2) {
            return bssid.substring(0, 8).replace(":", "").uppercase()
        } else if (bssid.length >= 6 && bssid.count { it == ':' } == 0) {
            return bssid.substring(0, 6).uppercase()
        }
        Log.w(TAG, "Could not extract valid OUI from BSSID: $bssid")
        return null
    }

    private suspend fun processScanResultsAndUpdateDb(
        scanResults: List<ScanResult>?,
        actualTimestampMillis: Long,
        currentCell: String,
        measurementTypeForThisScan: MeasurementType
    ) {
        var dbSuccess = false
        val logTagDb = "$TAG DB"
        Log.d(logTagDb, "Starting DB update. Cell: $currentCell, Type of this scan: $measurementTypeForThisScan, Timestamp: $actualTimestampMillis")

        try {
            withContext(Dispatchers.IO) {
                // 1. Create MeasurementTime entry WITH measurementTypeForThisScan
                val newMeasurementTimeEntry = MeasurementTime(
                    timestampMillis = actualTimestampMillis,
                    cell = currentCell,
                    measurementType = measurementTypeForThisScan // Assign type here
                )
                val newTimestampId = measurementTimeDao.insert(newMeasurementTimeEntry)
                Log.i(logTagDb, "New MeasurementTime ID: $newTimestampId for cell $currentCell, Type: $measurementTypeForThisScan")

                val results = scanResults ?: emptyList()
                // val knownApsToUpsert = mutableListOf<KnownAp>() // REMOVED

                // --- Aggregate ScanResults by BSSID Prime to get the strongest signal ---
                val strongestSignalPerPrimeInCurrentScan = mutableMapOf<String, ScanResult>()
                for (result in results) {
                    if (result.BSSID.isNullOrBlank()) continue
                    BssidUtil.calculateBssidPrime(result.BSSID)?.let { primeBssid ->
                        val existingStrongest = strongestSignalPerPrimeInCurrentScan[primeBssid]
                        if (existingStrongest == null || result.level > existingStrongest.level) {
                            strongestSignalPerPrimeInCurrentScan[primeBssid] = result
                        }
                    }
                }
                Log.d(logTagDb, "Aggregated ${strongestSignalPerPrimeInCurrentScan.size} unique prime BSSIDs from current scan.")

                val apMeasurementsForCurrentTimestamp = mutableListOf<ApMeasurement>()
                // val backfillApMeasurements = mutableListOf<ApMeasurement>() // REMOVED

                // Get all existing MeasurementTime IDs *before* this scan // REMOVED (no backfilling)
                // val allPreviousTimestampIds = measurementTimeDao.getAllTimestampIds().filter { it != newTimestampId } // REMOVED
                // Log.d(logTagDb, "Found ${allPreviousTimestampIds.size} previous timestamp IDs for potential backfilling.") // REMOVED


                // --- Process each unique prime BSSID found in the current scan ---
                for ((primeBssid, strongestResult) in strongestSignalPerPrimeInCurrentScan) {
                    val fullBssidForPrimeRep = strongestResult.BSSID // Retain for OUI extraction and potential logging
                    val currentSsidForPrime = strongestResult.SSID ?: ""
                    var determinedApTypeForPrime = ApType.MOBILE
                    extractOuiFromBssid(fullBssidForPrimeRep)?.let { oui ->
                        if (ouiDao.findByOui(oui) != null) determinedApTypeForPrime = ApType.FIXED
                    }

                    // Update/Insert KnownApPrime
                    val existingPrimeApEntry = knownApPrimeDao.findByBssidPrime(primeBssid)
                    val primeApToStore = KnownApPrime(
                        bssidPrime = primeBssid,
                        ssid = currentSsidForPrime,
                        apType = determinedApTypeForPrime
                    )
                    if (existingPrimeApEntry == null ||
                        existingPrimeApEntry.ssid != currentSsidForPrime ||
                        existingPrimeApEntry.apType != determinedApTypeForPrime) {
                        knownApPrimeDao.insertOrReplace(primeApToStore)
                        Log.d(logTagDb, "Upserted KnownApPrime: $primeBssid, SSID: $currentSsidForPrime, Type: $determinedApTypeForPrime")
                    }

                    // If this primeBssid was "newly discovered" (i.e., not in known_ap_prime before this scan)
                    // then backfill it for all *previous* timestamps. // REMOVED THIS ENTIRE BLOCK
                    // if (existingPrimeApEntry == null) {
                    //     Log.d(logTagDb, "Prime BSSID $primeBssid is newly discovered. Backfilling for ${allPreviousTimestampIds.size} previous timestamps.")
                    //     allPreviousTimestampIds.forEach { prevTimestampId ->
                    //         backfillApMeasurements.add(
                    //             ApMeasurement(
                    //                 bssidPrime = primeBssid,
                    //                 timestampId = prevTimestampId,
                    //                 rssi = DEFAULT_RSSI // Backfill with default RSSI
                    //             )
                    //         )
                    //     }
                    // }

                    // Add measurement for the current scan for this primeBssid
                    apMeasurementsForCurrentTimestamp.add(
                        ApMeasurement(
                            bssidPrime = primeBssid,
                            timestampId = newTimestampId,
                            rssi = strongestResult.level // This uses the strongest signal for the prime BSSID
                        )
                    )

                    // --- Handle original KnownAp table (full BSSIDs) --- // ENTIRE SECTION REMOVED
                    // ...
                } // End of for loop over strongestSignalPerPrimeInCurrentScan

                // --- Handle missing prime APs for the current timestamp ---
                // These are prime BSSIDs that are in known_ap_prime but were NOT in the current scan results.
                val allKnownPrimeBssidsInDb = knownApPrimeDao.getAll().map { it.bssidPrime }.toSet()
                val detectedPrimeBssidsInCurrentScanSet = strongestSignalPerPrimeInCurrentScan.keys
                val missingPrimeBssidsForCurrentScan = allKnownPrimeBssidsInDb - detectedPrimeBssidsInCurrentScanSet

//                Log.d(logTagDb, "${missingPrimeBssidsForCurrentScan.size} known prime APs were not detected in current scan. Adding with DEFAULT_RSSI for new timestamp $newTimestampId.")
//                missingPrimeBssidsForCurrentScan.forEach { primeBssid ->
//                    apMeasurementsForCurrentTimestamp.add(
//                        ApMeasurement(
//                            bssidPrime = primeBssid,
//                            timestampId = newTimestampId,
//                            rssi = DEFAULT_RSSI
//                        )
//                    )
//                }

                // --- Database Insertions ---
                // if (knownApsToUpsert.isNotEmpty()) { // REMOVED
                //     knownApDao.upsertAll(knownApsToUpsert.distinctBy { it.bssid }) // REMOVED
                //     Log.d(logTagDb, "Upserted ${knownApsToUpsert.distinctBy { it.bssid }.size} entries to known_aps.") // REMOVED
                // } // REMOVED

                val allMeasurementsToInsert = apMeasurementsForCurrentTimestamp.toMutableList()
                // allMeasurementsToInsert.addAll(backfillApMeasurements) // REMOVED

                if (allMeasurementsToInsert.isNotEmpty()) {
                    // Use distinctBy to handle potential overlaps (though less likely without backfilling)
                    val distinctMeasurements = allMeasurementsToInsert.distinctBy { it.bssidPrime to it.timestampId }
                    measurementDao.insertAll(distinctMeasurements)
                    Log.d(logTagDb, "Inserted ${distinctMeasurements.size} ApMeasurements for current scan.")
                } else {
                    Log.w(logTagDb, "No ApMeasurements to insert.")
                }

                // Batch mode count update
                if (isBatchModeActive && activeBatchMeasurementType != null) {
                    currentSampleCountForBatchType = measurementTimeDao.countScanEventsForCellAndType(currentCell, activeBatchMeasurementType!!)
                    Log.i(logTagDb, "Batch mode. Updated count for type ${activeBatchMeasurementType!!.name}: $currentSampleCountForBatchType")
                }
                dbSuccess = true
            } // End of withContext(Dispatchers.IO)
        } catch (e: Exception) {
            Log.e(logTagDb, "Error during database update for cell $currentCell: ${e.message}", e)
            dbSuccess = false
        }

        // --- Post-DB Update Logic (Main Thread) ---
        if (isAdded) {
            updateSampleCountDisplay() // Refreshes the general display

            if (dbSuccess) {
                Toast.makeText(requireContext(), "Scan for $currentCell ($measurementTypeForThisScan) recorded.", Toast.LENGTH_SHORT).show()
                if (isBatchModeActive && activeBatchMeasurementType != null) {
                    if (currentSampleCountForBatchType >= targetSampleCountForBatchType) {
                        val completedBatchType = activeBatchMeasurementType
                        stopBatchMode("Target count for ${completedBatchType!!.name} reached ($targetSampleCountForBatchType)")
                        Toast.makeText(requireContext(), "Batch mode complete for $currentCell (${completedBatchType.name})!", Toast.LENGTH_LONG).show()
                    } else {
                        Log.d(TAG, "Batch mode (${activeBatchMeasurementType!!.name}): $currentSampleCountForBatchType/$targetSampleCountForBatchType for $currentCell. Triggering next scan.")
                        checkPermissionsAndScan()
                    }
                }
            } else {
                Toast.makeText(requireContext(), "Database update failed for cell $currentCell.", Toast.LENGTH_SHORT).show()
                if (isBatchModeActive) {
                    stopBatchMode("Database update failed")
                }
            }
        } else {
            Log.w(TAG, "Fragment not added post DB update. UI not updated for cell $currentCell.")
        }
    }

    // --- Batch Mode Logic ---
    private fun showBatchInputDialog() {
        if (activeCellLabel == null) {
            Toast.makeText(context, "Select a cell first.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_batch_input, null)
        val editText = dialogView.findViewById<EditText>(R.id.edit_text_target_samples)
        val batchTypeForDialog = getMeasurementTypeForCurrentScan() // Type selected by switch for this new batch

        viewLifecycleOwner.lifecycleScope.launch {
            val currentCountForSelectedType = try {
                withContext(Dispatchers.IO) {
                    measurementTimeDao.countScanEventsForCellAndType(activeCellLabel!!, batchTypeForDialog)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching current count for batch dialog", e)
                Toast.makeText(context, "Error fetching current sample count.", Toast.LENGTH_SHORT).show()
                return@launch // Don't show dialog if count fetch fails
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Batch Scan for $activeCellLabel (${batchTypeForDialog.name})")
                .setMessage("Current ${batchTypeForDialog.name} samples: $currentCountForSelectedType. Enter target:")
                .setView(dialogView)
                .setPositiveButton("Start Batch") { _, _ ->
                    val inputText = editText.text.toString()
                    try {
                        val target = inputText.toInt()
                        if (target > currentCountForSelectedType) {
                            targetSampleCountForBatchType = target
                            currentSampleCountForBatchType = currentCountForSelectedType
                            activeBatchMeasurementType = batchTypeForDialog
                            startBatchMode()
                        } else {
                            Toast.makeText(context, "Target must be greater than current ${batchTypeForDialog.name} count ($currentCountForSelectedType).", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: NumberFormatException) {
                        Toast.makeText(context, "Invalid number entered.", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startBatchMode() {
        if (activeCellLabel == null || activeBatchMeasurementType == null) {
            Log.e(TAG, "Cannot start batch mode: cell or batch type not set.")
            return
        }
        isBatchModeActive = true
        updateScanButtonState()
        wifiResultsTextView.text = "Batch mode started for $activeCellLabel (Type: ${activeBatchMeasurementType!!.name}, Target: $targetSampleCountForBatchType)..."
        Log.i(TAG, "Batch mode started for $activeCellLabel, Type: ${activeBatchMeasurementType!!.name}, Target: $targetSampleCountForBatchType, Current for type: $currentSampleCountForBatchType")
        checkPermissionsAndScan()
    }

    private fun stopBatchMode(reason: String) {
        if (!isBatchModeActive) return
        isBatchModeActive = false
        targetSampleCountForBatchType = 0
        currentSampleCountForBatchType = 0
        activeBatchMeasurementType = null
        updateScanButtonState()
        wifiResultsTextView.text = "Batch mode stopped ($reason). Select cell and scan."
        Log.i(TAG, "Batch mode stopped. Reason: $reason")
    }


    // --- WiFi Throttling ---
    private fun attemptDisableWifiThrottling(manager: WifiManager?) {
        if (manager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                val method: Method = WifiManager::class.java.getDeclaredMethod("setWifiScanThrottlingEnabled", Boolean::class.javaPrimitiveType)
                method.isAccessible = true
                method.invoke(manager, false)
                Log.i(TAG, "Attempted to disable WiFi scan throttling via reflection.")
            } catch (e: NoSuchMethodException) {
                Log.w(TAG, "setWifiScanThrottlingEnabled method not found.", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Not allowed to disable WiFi scan throttling.", e)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable WiFi scan throttling via reflection.", e)
            }
        } else {
            Log.i(TAG, "WiFi scan throttling toggle not attempted or not supported on this API level (${Build.VERSION.SDK_INT}).")
        }
    }
}