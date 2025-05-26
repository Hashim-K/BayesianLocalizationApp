package com.example.assignment2.ui.db_view

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.example.assignment2.R
import com.example.assignment2.data.db.AccelMeasurement
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.db.KnownAp
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.data.db.OuiManufacturer // Import OUI entity
import com.example.assignment2.data.model.DatabaseBackup
import com.example.assignment2.data.model.MeasurementTimeViewItem
import com.example.assignment2.data.model.MeasurementViewItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class DbViewFragment : Fragment() {

    // --- UI Elements ---
    private lateinit var spinnerTableSelect: Spinner
    private lateinit var recyclerViewDbContent: RecyclerView
    private lateinit var textEmptyState: TextView
    private lateinit var layoutHeaders: LinearLayout
    private lateinit var textHeader1: TextView
    private lateinit var textHeader2: TextView
    private lateinit var textHeader3: TextView
    private lateinit var buttonSaveDb: Button
    private lateinit var buttonRestoreDb: Button
    private lateinit var buttonWipeDb: Button

    // --- Adapters and Utilities ---
    private lateinit var dbViewAdapter: DbViewAdapter
    private val gson = Gson()
    private val defaultBackupFileName = "wifi_db_backup.json"
    private val TAG = "DbViewFragment" // For logging

    // --- Database Instances ---
    private val appDb: AppDatabase by lazy {
        AppDatabase.getDatabase(requireContext().applicationContext)
    }
    private val knownApDao by lazy { appDb.knownApDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }
    private val measurementDao by lazy { appDb.apMeasurementDao() }
    private val measurementTimeDao by lazy { appDb.measurementTimeDao() }
    private val accelMeasurementDao by lazy { appDb.accelMeasurementDao() }
    private val ouiManufacturerDao by lazy { appDb.ouiManufacturerDao() } // Add OUI DAO

    // --- ActivityResultLaunchers for SAF ---
    private lateinit var createFileLauncher: ActivityResultLauncher<String>
    private lateinit var openFileLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFileLaunchers()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_db_view, container, false)
        spinnerTableSelect = view.findViewById(R.id.spinner_table_select)
        recyclerViewDbContent = view.findViewById(R.id.recycler_view_db_content)
        textEmptyState = view.findViewById(R.id.text_empty_state)
        layoutHeaders = view.findViewById(R.id.layout_headers)
        textHeader1 = view.findViewById(R.id.text_header1)
        textHeader2 = view.findViewById(R.id.text_header2)
        textHeader3 = view.findViewById(R.id.text_header3)
        buttonSaveDb = view.findViewById(R.id.button_save_db)
        buttonRestoreDb = view.findViewById(R.id.button_restore_db)
        buttonWipeDb = view.findViewById(R.id.button_wipe_db)

        setupRecyclerView()
        setupSpinner()
        setupActionButtons()
        return view
    }

    private fun setupFileLaunchers() {
        createFileLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) {
                Log.d(TAG, "SAF Save URI: $uri")
                actuallySaveDatabaseToFile(uri)
            } else {
                Log.d(TAG, "SAF Save: No URI selected")
                Toast.makeText(context, "Save cancelled.", Toast.LENGTH_SHORT).show()
            }
        }

        openFileLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                Log.d(TAG, "SAF Open URI: $uri")
                actuallyRestoreDatabaseFromFile(uri)
            } else {
                Log.d(TAG, "SAF Open: No URI selected")
                Toast.makeText(context, "Restore cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        dbViewAdapter = DbViewAdapter()
        recyclerViewDbContent.apply {
            adapter = dbViewAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupSpinner() {
        ArrayAdapter.createFromResource(
            requireContext(), R.array.database_tables, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTableSelect.adapter = adapter
        }
        spinnerTableSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                loadSelectedTableData(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {
                updateUiWithData(emptyList(), -1) // -1 indicates no specific table
            }
        }
        // Load initial data if adapter has items
        if (spinnerTableSelect.adapter.count > 0) {
            loadSelectedTableData(spinnerTableSelect.selectedItemPosition)
        }
    }

    private fun setupActionButtons() {
        buttonSaveDb.setOnClickListener { triggerSaveDatabase() }
        buttonRestoreDb.setOnClickListener { confirmRestoreDatabase() }
        buttonWipeDb.setOnClickListener { confirmWipeDatabase() }
    }

    // --- Save, Restore, Wipe Logic using SAF ---

    private fun triggerSaveDatabase() {
        try {
            createFileLauncher.launch(defaultBackupFileName)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching create file launcher", e)
            Toast.makeText(context, "Could not open file saver: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun actuallySaveDatabaseToFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Saving database...")
            var success = false
            var errorMessage: String? = null
            try {
                val backupData = withContext(Dispatchers.IO) {
                    DatabaseBackup(
                        knownAps = knownApDao.getAllKnownAps(),
                        knownApPrimes = knownApPrimeDao.getAll(),
                        measurementTimes = measurementTimeDao.getAllMeasurementTimes(),
                        apMeasurements = measurementDao.getAllRawMeasurementsOrdered(),
                        accelMeasurements = accelMeasurementDao.getAllAccelMeasurements()
                    )
                }
                val jsonString = gson.toJson(backupData)
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        OutputStreamWriter(outputStream).use { writer -> writer.write(jsonString) }
                    } ?: throw Exception("Failed to open output stream for URI.")
                }
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Error actually saving database to URI", e)
                errorMessage = e.localizedMessage ?: "Unknown error during save"
            }
            hideLoading()
            if (success) {
                Toast.makeText(context, "Database saved successfully.", Toast.LENGTH_LONG).show()
                loadSelectedTableData(spinnerTableSelect.selectedItemPosition)
            } else {
                Toast.makeText(context, "Failed to save database: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmRestoreDatabase() {
        AlertDialog.Builder(requireContext())
            .setTitle("Restore Database")
            .setMessage("This will wipe current data and restore from backup. Continue?")
            .setPositiveButton("Restore") { _, _ -> triggerRestoreDatabase() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerRestoreDatabase() {
        try {
            openFileLauncher.launch(arrayOf("application/json", "text/plain")) // Allow json or plain text
        } catch (e: Exception) {
            Log.e(TAG, "Error launching open file launcher", e)
            Toast.makeText(context, "Could not open file picker: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun actuallyRestoreDatabaseFromFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Restoring database...")
            var success = false
            var errorMessage: String? = null
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader -> reader.readText() }
                    } ?: throw Exception("Failed to open input stream for URI.")
                }
                val backupType = object : TypeToken<DatabaseBackup>() {}.type
                val backupData: DatabaseBackup = gson.fromJson(jsonString, backupType)

                withContext(Dispatchers.IO) {
                    appDb.withTransaction {
                        // Clear all tables
                        ouiManufacturerDao.clearTable() // Clear new table
                        accelMeasurementDao.clearTable()
                        measurementDao.clearTable()
                        measurementTimeDao.clearTable()
                        knownApPrimeDao.clearTable()
                        knownApDao.clearTable()
                        // Potentially clear ap_pmf if it were part of backup

                        // Restore data - order matters for foreign keys
                        if (backupData.knownAps.isNotEmpty()) knownApDao.upsertAll(backupData.knownAps)
                        if (backupData.knownApPrimes.isNotEmpty()) knownApPrimeDao.insertOrReplaceAll(backupData.knownApPrimes)
                        if (backupData.measurementTimes.isNotEmpty()) measurementTimeDao.insertAll(backupData.measurementTimes)
                        if (backupData.apMeasurements.isNotEmpty()) measurementDao.insertAll(backupData.apMeasurements)
                        if (backupData.accelMeasurements.isNotEmpty()) accelMeasurementDao.insertAll(backupData.accelMeasurements) }
                }
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Error actually restoring database from URI", e)
                errorMessage = e.localizedMessage ?: "Unknown error during restore"
            }
            hideLoading()
            if (success) {
                Toast.makeText(context, "Database restored successfully.", Toast.LENGTH_SHORT).show()
                loadSelectedTableData(spinnerTableSelect.selectedItemPosition) // Refresh view
            } else {
                Toast.makeText(context, "Failed to restore database: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmWipeDatabase() {
        AlertDialog.Builder(requireContext())
            .setTitle("Wipe Database")
            .setMessage("Are you sure you want to delete all data? This cannot be undone.")
            .setPositiveButton("Wipe") { _, _ -> wipeDatabase() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wipeDatabase() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading("Wiping database...")
            var success = false
            var errorMessage: String? = null
            try {
                withContext(Dispatchers.IO) {
                    appDb.withTransaction { // Use Room's transaction
                        // Clear user-generated data tables
                        accelMeasurementDao.clearTable()
                        measurementDao.clearTable()
                        measurementTimeDao.clearTable()
                        knownApDao.clearTable()
                        knownApPrimeDao.clearTable()
                        appDb.apPmfDao().clearTable() // Assuming you want to wipe this too
                        appDb.accelTestDataDao().clearTable() // Assuming you want to wipe this too

                        // DO NOT CLEAR OUI MANUFACTURERS
                        // ouiManufacturerDao.clearTable() // <-- REMOVE OR COMMENT OUT THIS LINE

                        Log.i(TAG, "User-generated tables wiped. OUI table preserved.")
                    }
                }
                success = true
            } catch (e: Exception) {
                Log.e(TAG, "Error wiping database", e)
                errorMessage = e.localizedMessage ?: "Unknown error"
            }
            hideLoading()
            if (success) {
                Toast.makeText(context, "User data wiped. OUI data preserved.", Toast.LENGTH_SHORT).show()
                loadSelectedTableData(spinnerTableSelect.selectedItemPosition) // Refresh view
            } else {
                Toast.makeText(context, "Failed to wipe database: $errorMessage", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoading(message: String) {
        textEmptyState.text = message
        textEmptyState.visibility = View.VISIBLE
        recyclerViewDbContent.visibility = View.GONE
        layoutHeaders.visibility = View.GONE
        buttonSaveDb.isEnabled = false
        buttonRestoreDb.isEnabled = false
        buttonWipeDb.isEnabled = false
        spinnerTableSelect.isEnabled = false
    }

    private fun hideLoading() {
        buttonSaveDb.isEnabled = true
        buttonRestoreDb.isEnabled = true
        buttonWipeDb.isEnabled = true
        spinnerTableSelect.isEnabled = true
        // Visibility of RecyclerView and headers will be handled by updateUiWithData
    }

    private fun loadSelectedTableData(position: Int) {
        showLoading(getString(R.string.db_view_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            var dataToDisplay: List<Any> = emptyList()
            var errorOccurred = false
            var errorMessage: String? = null
            try {
                dataToDisplay = when (position) {
                    0 -> loadKnownApsData()
                    1 -> loadKnownApPrimesData()
                    2 -> loadMeasurementsData() // Individual AP readings
                    3 -> loadMeasurementTimesViewData() // New: Overview of scan events
                    4 -> loadAccelMeasurementsData()
                    5 -> loadOuiManufacturersData()
                    else -> emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading data for table index $position", e)
                errorOccurred = true
                errorMessage = e.localizedMessage ?: "Unknown error fetching data"
            }
            hideLoading()
            if (errorOccurred) {
                textEmptyState.text = getString(R.string.db_view_error) + (errorMessage?.let { "\n$it" } ?: "")
                textEmptyState.visibility = View.VISIBLE
                recyclerViewDbContent.visibility = View.GONE
                layoutHeaders.visibility = View.GONE
            } else {
                updateUiWithData(dataToDisplay, position)
            }
        }
    }

    private fun updateUiWithData(data: List<Any>, tableIndex: Int) {
        dbViewAdapter.submitList(data)
        if (data.isEmpty()) {
            textEmptyState.text = getString(R.string.db_view_empty)
            textEmptyState.visibility = View.VISIBLE
            recyclerViewDbContent.visibility = View.GONE
            layoutHeaders.visibility = View.GONE
        } else {
            textEmptyState.visibility = View.GONE
            recyclerViewDbContent.visibility = View.VISIBLE
            updateHeaders(tableIndex)
            layoutHeaders.visibility = View.VISIBLE
        }
    }

    private fun updateHeaders(tableIndex: Int) {
        when (tableIndex) {
            0 -> { // Known APs
                textHeader1.text = "BSSID"
                textHeader2.text = "SSID"
                textHeader3.text = "AP Type"
            }
            1-> { // Known APs (Prime)
                textHeader1.text = "Prime BSSID"
                textHeader2.text = "SSID"
                textHeader3.text = "AP Type"
            }
            2 -> { // WiFi Measurements (Individual AP readings)
                textHeader1.text = "Prime BSSID"
                textHeader2.text = "Timestamp [Cell] (Type)"
                textHeader3.text = "RSSI"
            }
            3 -> { // Measurement Times (Overview of scan events)
                textHeader1.text = "Timestamp"
                textHeader2.text = "Cell"
                textHeader3.text = "Scan Type" // Header for measurement_type
            }
            4 -> { // Accelerometer Measurements
                textHeader1.text = "Timestamp"
                textHeader2.text = "Activity"
                textHeader3.text = "X | Y | Z (MaxMin)"
            }
            5 -> { // OUI Manufacturers
                textHeader1.text = "OUI"
                textHeader2.text = "Short Name"
                textHeader3.text = "Full Name"
            }
            else -> {
                textHeader1.text = ""
                textHeader2.text = ""
                textHeader3.text = ""
            }
        }
    }

    // --- Data Loading Functions ---
    private suspend fun loadKnownApsData(): List<KnownAp> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading Known APs data from DB.")
        return@withContext knownApDao.getAllKnownAps()
    }

    private suspend fun loadKnownApPrimesData(): List<KnownApPrime> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading Known AP Primes data from DB.")
        return@withContext knownApPrimeDao.getAll()
    }

    private suspend fun loadMeasurementsData(): List<MeasurementViewItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading WiFi Measurements data from DB.")
        return@withContext measurementDao.getAllMeasurementsForView()
    }

    private suspend fun loadMeasurementTimesViewData(): List<MeasurementTimeViewItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading Measurement Times (ViewItem) data from DB.")
        return@withContext measurementTimeDao.getAllMeasurementTimesForView()
    }

    private suspend fun loadAccelMeasurementsData(): List<AccelMeasurement> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading Accelerometer Measurements data from DB.")
        return@withContext accelMeasurementDao.getAllAccelMeasurements()
    }

    // New function to load OUI data
    private suspend fun loadOuiManufacturersData(): List<OuiManufacturer> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading OUI Manufacturers data from DB.")
        return@withContext ouiManufacturerDao.getAllOuiManufacturers() // Need to add this to DAO
    }
}
