package com.example.assignment2.ui.pmf // Or your preferred package

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.ApPmf
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.data.model.ApType
import com.example.assignment2.util.Histogram
import com.example.assignment2.util.HistogramManager
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class PmfViewFragment : Fragment() {
    private val TAG = "PmfViewFragment"

    // UI Elements
    private lateinit var buttonGeneratePmf: Button
    private lateinit var buttonPmfSettings: ImageButton
    private lateinit var buttonClearPmfTable: ImageButton
    private lateinit var spinnerSelectBssidPmfView: Spinner
    private lateinit var buttonPrevBssidPmf: ImageButton
    private lateinit var buttonNextBssidPmf: ImageButton
    private lateinit var seekBarViewBinWidth: SeekBar // For viewing a specific bin width PMF
    private lateinit var labelViewBinWidth: TextView
    private lateinit var textPmfStatus: TextView
    private lateinit var tableLayoutPmfHeatmap: TableLayout
    // private lateinit var barChartHistogram: BarChart // Keep if you want to toggle between heatmap and bar chart

    // DAOs and Managers
    private val appDb by lazy { AppDatabase.getDatabase(requireContext().applicationContext) }
    private val apPmfDao by lazy { appDb.apPmfDao() }
    private val knownApPrimeDao by lazy { appDb.knownApPrimeDao() }
    private lateinit var histogramManager: HistogramManager

    // State
    private var minBinWidthGenerate: Int = 1
    private var maxBinWidthGenerate: Int = 5
    private var selectedFeature: String = "None"

    private var availableFixedBssidsDisplayInfo: List<BssidDisplayInfoForPmf> = emptyList()
    private var availableFixedBssidsPrime: List<String> = emptyList()
    private var selectedBssidPrimeForView: String? = null
    private var currentViewBinWidth: Int = 1 // For the heatmap/table view
    private var knownApPrimeMap: Map<String, KnownApPrime> = emptyMap()

    private val displayCells = listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10")

    data class BssidDisplayInfoForPmf(val bssidPrime: String, val displayName: String)

    // Heatmap colors
    private var heatmapMinColor: Int = Color.TRANSPARENT // Or a very light color
    private var heatmapMaxColor: Int = Color.GREEN // Example: Bright Green for PMF = 1.0

    private var activeCellColorForTheme: Int = 0 // To store the theme's active color

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_pmf_view, container, false)

        histogramManager = HistogramManager(appDb.measurementTimeDao(), appDb.apMeasurementDao()) // Default bin width not critical here

        buttonGeneratePmf = root.findViewById(R.id.button_generate_pmf)
        buttonPmfSettings = root.findViewById(R.id.button_pmf_settings)
        buttonClearPmfTable = root.findViewById(R.id.button_clear_pmf_table)
        spinnerSelectBssidPmfView = root.findViewById(R.id.spinner_select_bssid_pmf_view)
        buttonPrevBssidPmf = root.findViewById(R.id.button_prev_bssid_pmf)
        buttonNextBssidPmf = root.findViewById(R.id.button_next_bssid_pmf)
        seekBarViewBinWidth = root.findViewById(R.id.seekbar_view_bin_width)
        labelViewBinWidth = root.findViewById(R.id.label_view_bin_width)
        textPmfStatus = root.findViewById(R.id.text_pmf_status)
        tableLayoutPmfHeatmap = root.findViewById(R.id.table_layout_pmf_heatmap)

        // Initialize heatmap colors (could also be from theme attributes)
        heatmapMinColor = ContextCompat.getColor(requireContext(), android.R.color.transparent) // Or a very light theme color
        // Consider getting this from theme attributes for better dark/light mode adaptation
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
        heatmapMaxColor = typedValue.data // Use primary color for max PMF

        // Initialize activeCellColorForTheme
        requireContext().theme.resolveAttribute(R.attr.cellActiveColor, typedValue, true) // Assuming you have this attr
        activeCellColorForTheme = if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data // it's a direct color value
        }

        buttonPmfSettings = root.findViewById(R.id.button_pmf_settings)
        buttonClearPmfTable = root.findViewById(R.id.button_clear_pmf_table)


        // Style settings button
        buttonPmfSettings.setColorFilter(activeCellColorForTheme)

        setupViewBinWidthSlider()
        setupActionListeners()
        checkPmfTableAndUpdateButton()
        loadAvailableFixedBssidsForSpinner() // Load initially

        return root
    }

    private fun setupViewBinWidthSlider() {
        labelViewBinWidth.text = "View PMF for Bin Width: $currentViewBinWidth"
        seekBarViewBinWidth.progress = currentViewBinWidth
        seekBarViewBinWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentViewBinWidth = progress.coerceAtLeast(1)
                labelViewBinWidth.text = "View PMF for Bin Width: $currentViewBinWidth"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                displayPmfHeatmap() // Refresh heatmap with new view bin width
            }
        })
    }

    private fun setupActionListeners() {
        buttonGeneratePmf.setOnClickListener {
            triggerPmfGeneration()
        }
        buttonPmfSettings.setOnClickListener {
            showPmfSettingsDialog()
        }
        buttonClearPmfTable.setOnClickListener {
            confirmClearPmfTable()
        }
        buttonPrevBssidPmf.setOnClickListener { cycleBssidSelection(false) }
        buttonNextBssidPmf.setOnClickListener { cycleBssidSelection(true) }

        spinnerSelectBssidPmfView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && position -1 < availableFixedBssidsPrime.size) {
                    val newlySelected = availableFixedBssidsPrime[position - 1]
                    if (newlySelected != selectedBssidPrimeForView) {
                        selectedBssidPrimeForView = newlySelected
                        Log.d(TAG, "BSSID for PMF view selected: $selectedBssidPrimeForView")
                        displayPmfHeatmap()
                    }
                } else if (position == 0 && selectedBssidPrimeForView != null) {
                    selectedBssidPrimeForView = null
                    displayPmfHeatmap()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBssidPrimeForView = null
                displayPmfHeatmap()
            }
        }
    }

    private fun cycleBssidSelection(next: Boolean) {
        if (availableFixedBssidsPrime.isEmpty()) return
        var currentIndex = if (selectedBssidPrimeForView != null) availableFixedBssidsPrime.indexOf(selectedBssidPrimeForView) else -1

        if (next) {
            currentIndex = (currentIndex + 1) % availableFixedBssidsPrime.size
        } else {
            currentIndex = (currentIndex - 1 + availableFixedBssidsPrime.size) % availableFixedBssidsPrime.size
        }
        spinnerSelectBssidPmfView.setSelection(currentIndex + 1, true) // +1 for prompt
    }


    private fun checkPmfTableAndUpdateButton() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { apPmfDao.getAllPmfs().size }
            buttonGeneratePmf.text = if (count > 0) "Regenerate PMFs" else "Generate PMFs"

            if (count > 0) {
                buttonClearPmfTable.isEnabled = true
                buttonClearPmfTable.setColorFilter(Color.RED) // Make icon red
                buttonClearPmfTable.alpha = 1.0f
            } else {
                buttonClearPmfTable.isEnabled = false
                buttonClearPmfTable.clearColorFilter() // Remove red tint
                buttonClearPmfTable.alpha = 0.5f // Make it look disabled
                textPmfStatus.text = "PMF table is empty. Generate PMFs to view data."
                tableLayoutPmfHeatmap.removeAllViews()
            }
        }
    }

    private fun loadAvailableFixedBssidsForSpinner() {
        lifecycleScope.launch {
            knownApPrimeMap = withContext(Dispatchers.IO) {
                knownApPrimeDao.getAll().filter { it.apType == ApType.FIXED }.associateBy { it.bssidPrime }
            }
            // Initially, populate with all fixed APs. After generation, we might refine this
            // to only show BSSIDs for which PMFs were actually generated.
            // For now, let's show all fixed APs.
            availableFixedBssidsDisplayInfo = knownApPrimeMap.values
                .map { BssidDisplayInfoForPmf(it.bssidPrime, "${it.ssid ?: "N/A"} (${it.bssidPrime})") }
                .sortedBy { it.displayName }
            availableFixedBssidsPrime = availableFixedBssidsDisplayInfo.map { it.bssidPrime }

            populateBssidPmfSpinner()
            updateBssidArrowButtonStates()
        }
    }
    private fun refreshAvailableBssidsForSpinnerAfterGeneration() {
        lifecycleScope.launch {
            val pmfBssids = withContext(Dispatchers.IO) {
                apPmfDao.getAllPmfs().map { it.bssidPrime }.distinct()
            }
            knownApPrimeMap = withContext(Dispatchers.IO) { // Ensure map is up-to-date
                knownApPrimeDao.getAll().filter { it.apType == ApType.FIXED }.associateBy { it.bssidPrime }
            }

            availableFixedBssidsDisplayInfo = pmfBssids
                .mapNotNull { bssidPrime ->
                    knownApPrimeMap[bssidPrime]?.let { knownAp -> // Only include if it's a known fixed AP
                        BssidDisplayInfoForPmf(bssidPrime, "${knownAp.ssid ?: "N/A"} ($bssidPrime)")
                    }
                }
                .sortedBy { it.displayName }
            availableFixedBssidsPrime = availableFixedBssidsDisplayInfo.map { it.bssidPrime }

            val previouslySelected = selectedBssidPrimeForView
            populateBssidPmfSpinner() // This will reset selection to prompt if old one is gone
            // Try to reselect if still valid
            if (previouslySelected != null && availableFixedBssidsPrime.contains(previouslySelected)) {
                val idx = availableFixedBssidsPrime.indexOf(previouslySelected)
                spinnerSelectBssidPmfView.setSelection(idx + 1, false)
                selectedBssidPrimeForView = previouslySelected
            } else if (availableFixedBssidsPrime.isNotEmpty()){
                spinnerSelectBssidPmfView.setSelection(1, true) // Select first, trigger display
            } else {
                selectedBssidPrimeForView = null // No BSSIDs with PMFs
                displayPmfHeatmap() // Clear heatmap
            }
            updateBssidArrowButtonStates()
        }
    }


    private fun populateBssidPmfSpinner() {
        val displayNamesWithPrompt = listOf("Select BSSID for PMF") + availableFixedBssidsDisplayInfo.map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNamesWithPrompt)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSelectBssidPmfView.adapter = adapter
        spinnerSelectBssidPmfView.isEnabled = availableFixedBssidsDisplayInfo.isNotEmpty()
        updateBssidArrowButtonStates()
    }

    private fun updateBssidArrowButtonStates() {
        val enabled = availableFixedBssidsPrime.isNotEmpty()
        buttonPrevBssidPmf.isEnabled = enabled
        buttonNextBssidPmf.isEnabled = enabled
        buttonPrevBssidPmf.alpha = if (enabled) 1.0f else 0.5f
        buttonNextBssidPmf.alpha = if (enabled) 1.0f else 0.5f
    }


    private fun showPmfSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_pmf_settings, null)
        val rangeSliderBinWidth = dialogView.findViewById<RangeSlider>(R.id.rangeslider_bin_width_generate)
        val labelRange = dialogView.findViewById<TextView>(R.id.label_bin_width_range_generate) // Ensure this ID exists in dialog
        val spinnerFeatures = dialogView.findViewById<Spinner>(R.id.spinner_pmf_features)

        // Initialize RangeSlider
        rangeSliderBinWidth.valueFrom = 1f
        rangeSliderBinWidth.valueTo = 20f
        rangeSliderBinWidth.stepSize = 1f
        rangeSliderBinWidth.setValues(minBinWidthGenerate.toFloat(), maxBinWidthGenerate.toFloat())
        labelRange.text = "Bin Width Range for Generation: $minBinWidthGenerate - $maxBinWidthGenerate"

        rangeSliderBinWidth.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            val currentMin = values[0].toInt()
            val currentMax = values[1].toInt()
            labelRange.text = "Bin Width Range for Generation: $currentMin - $currentMax"
        }

        ArrayAdapter.createFromResource(
            requireContext(), R.array.pmf_features, android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerFeatures.adapter = adapter
            val currentFeatureIndex = (spinnerFeatures.adapter as ArrayAdapter<String>).getPosition(selectedFeature)
            spinnerFeatures.setSelection(if(currentFeatureIndex != -1) currentFeatureIndex else 0)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("PMF Generation Settings")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val values = rangeSliderBinWidth.values
                val newMin = values[0].toInt()
                val newMax = values[1].toInt()

                if (newMax < newMin) { // Should not happen if slider enforces this
                    Toast.makeText(context, "Max bin width cannot be less than Min.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                minBinWidthGenerate = newMin
                maxBinWidthGenerate = newMax
                selectedFeature = spinnerFeatures.selectedItem.toString()
                Toast.makeText(context, "Settings Applied. Range: $minBinWidthGenerate-$maxBinWidthGenerate", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearPmfTable() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear PMF Table")
            .setMessage("Are you sure you want to delete all generated PMFs?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { apPmfDao.clearTable() }
                    Toast.makeText(context, "PMF table cleared.", Toast.LENGTH_SHORT).show()
                    checkPmfTableAndUpdateButton() // Update button states
                    refreshAvailableBssidsForSpinnerAfterGeneration()
                    displayPmfHeatmap()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerPmfGeneration() {
        textPmfStatus.text = "Generating PMFs for bin widths $minBinWidthGenerate to $maxBinWidthGenerate..."
        tableLayoutPmfHeatmap.removeAllViews()
        buttonGeneratePmf.isEnabled = false

        lifecycleScope.launch {
            val fixedAps = withContext(Dispatchers.IO) {
                knownApPrimeDao.getAll().filter { it.apType == ApType.FIXED }
            }
            if (fixedAps.isEmpty()) {
                textPmfStatus.text = "No fixed APs found in Known APs (Prime) table."
                buttonGeneratePmf.isEnabled = true
                return@launch
            }

            var pmfsGeneratedCount = 0
            // This uses a new HistogramManager for each binWidth to ensure correct internal state
            // Could be optimized if HistogramManager was designed to change binWidth on the fly
            for (binW in minBinWidthGenerate..maxBinWidthGenerate) {
                val tempHistManager = HistogramManager(
                    appDb.measurementTimeDao(),
                    appDb.apMeasurementDao(),
                    defaultBinWidth = binW
                )
                Log.d(TAG, "Generating PMFs for bin width: $binW")
                tempHistManager.loadAndProcessAllHistograms() // This loads all data for all cells/bssids

                if (!tempHistManager.isDataLoaded) {
                    Log.w(TAG, "Histogram data failed to load for bin width $binW")
                    continue
                }

                val pmfsToInsert = mutableListOf<ApPmf>()
                fixedAps.forEach { fixedAp ->
                    val histogramsForBssid = tempHistManager.getAllHistogramsByCell()
                        .mapNotNull { (cell, bssidMap) ->
                            bssidMap[fixedAp.bssidPrime]?.let { hist -> cell to hist }
                        }.toMap()

                    histogramsForBssid.forEach { (cell, histogram) ->
                        if (histogram.totalCount > 0) {
                            pmfsToInsert.add(
                                ApPmf(
                                    bssidPrime = fixedAp.bssidPrime,
                                    cell = cell,
                                    binWidth = histogram.binWidth, // Should be binW
                                    binsData = histogram.bins
                                )
                            )
                        }
                    }
                }
                if (pmfsToInsert.isNotEmpty()) {
                    withContext(Dispatchers.IO) { apPmfDao.insertOrReplaceAll(pmfsToInsert) }
                    pmfsGeneratedCount += pmfsToInsert.size
                }
            } // End of binWidth loop

            Toast.makeText(context, "$pmfsGeneratedCount PMF entries generated/updated.", Toast.LENGTH_LONG).show()
            checkPmfTableAndUpdateButton() // Update button states after generation
            refreshAvailableBssidsForSpinnerAfterGeneration()
            displayPmfHeatmap()
            buttonGeneratePmf.isEnabled = true
        }
    }


    private fun displayPmfHeatmap() {
        tableLayoutPmfHeatmap.removeAllViews() // Clear previous table

        val currentSelectedBssid = selectedBssidPrimeForView // Capture the value

        if (currentSelectedBssid == null) { // Check the captured value
            textPmfStatus.text = "Select a BSSID Prime to view PMFs."
            return
        }
        textPmfStatus.text = "Loading PMF for $currentSelectedBssid (Bin Width: $currentViewBinWidth)..."

        lifecycleScope.launch {
            val pmfDataForCells = mutableMapOf<String, Map<Int, Double>>()
            val allBinStartsAcrossCells = mutableSetOf<Int>()

            for (cellLabel in displayCells) {
                val apPmf = withContext(Dispatchers.IO) {
                    // Use the captured value 'currentSelectedBssid'
                    apPmfDao.getPmf(currentSelectedBssid, cellLabel, currentViewBinWidth)
                }
                if (apPmf != null && apPmf.binsData.isNotEmpty()) {
                    val totalCount = apPmf.binsData.values.sum()
                    if (totalCount > 0) {
                        val pmf = apPmf.binsData.mapValues { (_, count) -> count.toDouble() / totalCount }
                        pmfDataForCells[cellLabel] = pmf
                        allBinStartsAcrossCells.addAll(pmf.keys)
                    } else {
                        pmfDataForCells[cellLabel] = emptyMap()
                    }
                } else {
                    pmfDataForCells[cellLabel] = emptyMap()
                }
            }

            if (pmfDataForCells.all { it.value.isEmpty() } && allBinStartsAcrossCells.isEmpty()) {
                textPmfStatus.text = "No PMF data found for $currentSelectedBssid with bin width $currentViewBinWidth."
                return@launch
            }
            textPmfStatus.text = "Displaying PMF for $currentSelectedBssid (Bin Width: $currentViewBinWidth)"
            val sortedBinStarts = allBinStartsAcrossCells.sorted()

            // Create Header Row
            val headerRow = TableRow(context)
            headerRow.addView(createTableCell("Cell", isHeader = true, isCorner = true))
            sortedBinStarts.forEach { binStart ->
                headerRow.addView(createTableCell(binStart.toString(), isHeader = true))
            }
            tableLayoutPmfHeatmap.addView(headerRow)

            // Create Data Rows
            displayCells.forEach { cellLabel ->
                val row = TableRow(context)
                row.addView(createTableCell(cellLabel, isHeader = true))
                val cellPmf = pmfDataForCells[cellLabel] ?: emptyMap()
                sortedBinStarts.forEach { binStart ->
                    val pmfValue = cellPmf[binStart] ?: 0.0
                    row.addView(createTableCell(String.format(Locale.US, "%.2f", pmfValue), pmfValue = pmfValue))
                }
                tableLayoutPmfHeatmap.addView(row)
            }
        }
    }

    private fun createTableCell(text: String, isHeader: Boolean = false, isCorner: Boolean = false, pmfValue: Double? = null): TextView {
        val textView = TextView(context)
        textView.text = text
        textView.setPadding(12, 8, 12, 8) // Increased padding
        textView.gravity = Gravity.CENTER
        val params = TableRow.LayoutParams(TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT)
        params.setMargins(1,1,1,1)
        textView.layoutParams = params

        val typedValue = TypedValue()
        val theme = requireContext().theme
        var cellTextColor = Color.BLACK // Default

        if (isHeader) {
            theme.resolveAttribute(if (isCorner) R.attr.colorSurface else R.attr.colorSurfaceVariant, typedValue, true)
            textView.setBackgroundColor(typedValue.data)
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            cellTextColor = ContextCompat.getColor(requireContext(), typedValue.resourceId)
            textView.setTypeface(null, Typeface.BOLD)
        } else if (pmfValue != null) {
            // Heatmap color based on PMF value
            val blendedColor = ColorUtils.blendARGB(heatmapMinColor, heatmapMaxColor, pmfValue.toFloat())
            textView.setBackgroundColor(blendedColor)
            // Determine text color for contrast against heatmap color
            cellTextColor = if (ColorUtils.calculateLuminance(blendedColor) < 0.5) Color.WHITE else Color.BLACK
        } else { // Default data cell
            theme.resolveAttribute(android.R.attr.colorBackground, typedValue, true)
            textView.setBackgroundColor(typedValue.data)
            theme.resolveAttribute(android.R.attr.textColorSecondary, typedValue, true)
            cellTextColor = ContextCompat.getColor(requireContext(), typedValue.resourceId)
        }
        textView.setTextColor(cellTextColor)
        textView.minWidth = 80 // Ensure a minimum width for cells
        return textView
    }
}