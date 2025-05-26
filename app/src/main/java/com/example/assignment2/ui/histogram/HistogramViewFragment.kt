package com.example.assignment2.ui.histogram

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.assignment2.R
import com.example.assignment2.data.db.AppDatabase
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.util.Histogram
import com.example.assignment2.util.HistogramManager
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistogramViewFragment : Fragment() {
    private val TAG = "HistogramViewFragment"

    // UI Elements
    private lateinit var buttonGenerateHistograms: Button
    private lateinit var spinnerSelectCell: Spinner
    private lateinit var spinnerSelectBssid: Spinner
    private lateinit var textHistogramStatus: TextView
    private lateinit var textHistogramDetails: TextView
    private lateinit var seekBarBinWidth: SeekBar
    private lateinit var labelBinWidth: TextView
    private lateinit var barChartHistogram: BarChart
    private lateinit var buttonPrevCell: ImageButton
    private lateinit var buttonNextCell: ImageButton

    // Histogram Management
    private lateinit var histogramManager: HistogramManager
    private var allHistogramsByCell: Map<String, Map<String, Histogram>> = emptyMap()
    private var sortedAvailableCells: List<String> = emptyList() // Will be sorted C1, C2...
    private var availableBssidsForSelectedCell: List<String> = emptyList()

    private var selectedCell: String? = null
    private var selectedBssid: String? = null
    private var currentBinWidth: Int = 1

    private val knownApPrimeDao by lazy { AppDatabase.getDatabase(requireContext()).knownApPrimeDao() }
    private var knownApPrimeMap: Map<String, KnownApPrime> = emptyMap()

    data class BssidDisplayInfo(
        val bssidPrime: String,
        val displayName: String,
        val averageRssi: Double
    )
    private var availableBssidsDisplayInfo: List<BssidDisplayInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_histogram_view, container, false)

        val appDb = AppDatabase.getDatabase(requireContext().applicationContext)
        // Initialize HistogramManager with the currentBinWidth
        histogramManager = HistogramManager(
            appDb.measurementTimeDao(),
            appDb.apMeasurementDao(),
            defaultBinWidth = currentBinWidth
        )

        buttonGenerateHistograms = root.findViewById(R.id.button_generate_histograms)
        spinnerSelectCell = root.findViewById(R.id.spinner_select_cell_hist)
        spinnerSelectBssid = root.findViewById(R.id.spinner_select_bssid_hist)
        textHistogramStatus = root.findViewById(R.id.text_histogram_status)
        textHistogramDetails = root.findViewById(R.id.text_histogram_details)
        seekBarBinWidth = root.findViewById(R.id.seekbar_bin_width)
        labelBinWidth = root.findViewById(R.id.label_bin_width)
        barChartHistogram = root.findViewById(R.id.bar_chart_histogram)
        buttonPrevCell = root.findViewById(R.id.button_prev_cell) // Init
        buttonNextCell = root.findViewById(R.id.button_next_cell) // Init

        setupSeekBar()
        setupListeners()
        setupBarChart()
        updateSpinnersAndDisplay()

        if (histogramManager.isDataLoaded) {
            handleHistogramsLoaded(false) // Don't reset selections if already loaded
        } else {
            textHistogramStatus.text = "Press 'Generate' to load histogram data."
            barChartHistogram.visibility = View.GONE
        }
        return root
    }

    private fun setupSeekBar() {
        labelBinWidth.text = "Bin Width: $currentBinWidth"
        seekBarBinWidth.progress = currentBinWidth // Ensure seekbar matches currentBinWidth
        seekBarBinWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBinWidth = progress.coerceAtLeast(1)
                labelBinWidth.text = "Bin Width: $currentBinWidth"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Re-generate histograms with the new bin width
                // Keep current cell/bssid selections if they exist
                Toast.makeText(context, "Updating with bin width: $currentBinWidth", Toast.LENGTH_SHORT).show()
                triggerHistogramGeneration(resetSelections = false) // Don't reset selections
            }
        })
    }

    private fun setupListeners() {
        buttonGenerateHistograms.setOnClickListener {
            triggerHistogramGeneration(resetSelections = true)
        }

        buttonPrevCell.setOnClickListener {
            cycleCellSelection(false) // false for previous
        }

        buttonNextCell.setOnClickListener {
            cycleCellSelection(true) // true for next
        }

        spinnerSelectCell.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // The prompt "Select Cell" is at position 0
                if (position > 0 && position -1 < sortedAvailableCells.size) {
                    val newlySelectedCell = sortedAvailableCells[position - 1]
                    if (newlySelectedCell != selectedCell) { // Only update if selection actually changed
                        selectedCell = newlySelectedCell
                        Log.d(TAG, "Cell selected via spinner: $selectedCell")
                        populateBssidSpinner(selectedCell!!)
                        selectedBssid = null // Reset BSSID selection when cell changes
                        displaySelectedHistogram()
                    }
                } else if (position == 0 && selectedCell != null) { // User selected the prompt
                    selectedCell = null
                    populateBssidSpinner(null)
                    selectedBssid = null
                    displaySelectedHistogram()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedCell = null
                populateBssidSpinner(null)
                selectedBssid = null
                displaySelectedHistogram()
            }
        }

        spinnerSelectBssid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0 && selectedCell != null && position -1 < availableBssidsForSelectedCell.size) {
                    val newlySelectedBssid = availableBssidsForSelectedCell[position - 1]
                    if (newlySelectedBssid != selectedBssid) {
                        selectedBssid = newlySelectedBssid
                        Log.d(TAG, "BSSID selected: $selectedBssid for cell $selectedCell")
                        displaySelectedHistogram()
                    }
                } else if (position == 0 && selectedBssid != null) {
                    selectedBssid = null
                    displaySelectedHistogram()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBssid = null
                displaySelectedHistogram()
            }
        }
    }

    private fun triggerHistogramGeneration(resetSelections: Boolean) {
        textHistogramStatus.text = "Generating histograms (Bin Width: $currentBinWidth)..."
        textHistogramDetails.text = ""
        barChartHistogram.clear()
        barChartHistogram.visibility = View.GONE

        if (resetSelections) {
            selectedCell = null
            selectedBssid = null
        }

        histogramManager = HistogramManager(
            AppDatabase.getDatabase(requireContext()).measurementTimeDao(),
            AppDatabase.getDatabase(requireContext()).apMeasurementDao(),
            defaultBinWidth = currentBinWidth
        )

        lifecycleScope.launch {
            knownApPrimeMap = withContext(Dispatchers.IO) {
                knownApPrimeDao.getAll().associateBy { it.bssidPrime }
            }
            histogramManager.loadAndProcessAllHistograms()
            if (histogramManager.isDataLoaded) {
                if (resetSelections) { // Only show this toast on full refresh
                    Toast.makeText(context, "Histograms generated/refreshed.", Toast.LENGTH_SHORT).show()
                }
                handleHistogramsLoaded(resetSelections)
            } else {
                Toast.makeText(context, "Failed to generate histograms.", Toast.LENGTH_SHORT).show()
                textHistogramStatus.text = "Error generating histograms. Check logs."
            }
        }
    }


    private fun handleHistogramsLoaded(wasFullReset: Boolean) {
        allHistogramsByCell = histogramManager.getAllHistogramsByCell()
        sortedAvailableCells = allHistogramsByCell.keys.sortedWith(compareBy {
            it.replace("C", "").toIntOrNull() ?: Int.MAX_VALUE
        })

        val oldSelectedCell = selectedCell // Preserve before repopulating spinners
        val oldSelectedBssid = selectedBssid

        populateCellSpinner() // This will set its own selection based on oldSelectedCell

        // If it was a full reset or the old cell is no longer valid, select the first cell if available
        if (wasFullReset || (oldSelectedCell != null && !sortedAvailableCells.contains(oldSelectedCell))) {
            if (sortedAvailableCells.isNotEmpty()) {
                spinnerSelectCell.setSelection(1) // Select first actual cell, triggers its listener
            } else {
                populateBssidSpinner(null) // No cells, clear BSSID spinner
            }
        } else if (oldSelectedCell != null) {
            // Cell spinner already set, now populate BSSID spinner for it
            populateBssidSpinner(oldSelectedCell)
            // And try to re-select the old BSSID if it's still valid for this cell
            if (oldSelectedBssid != null && availableBssidsForSelectedCell.contains(oldSelectedBssid)) {
                val bssidIdx = availableBssidsForSelectedCell.indexOf(oldSelectedBssid)
                if (bssidIdx != -1) {
                    spinnerSelectBssid.setSelection(bssidIdx + 1, false)
                }
            }
        } else {
            populateBssidSpinner(null) // No cell was selected
        }

        displaySelectedHistogram() // Update display based on current (possibly re-selected) items
        textHistogramStatus.text = if (sortedAvailableCells.isEmpty()) "No histogram data found." else "Select a cell and BSSID."
        updateArrowButtonStates()
    }


    private fun populateCellSpinner() {
        val cellsWithPrompt = listOf("Select Cell") + sortedAvailableCells
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cellsWithPrompt)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSelectCell.adapter = adapter
        spinnerSelectCell.isEnabled = sortedAvailableCells.isNotEmpty()

        // Try to re-select the previously selected cell
        if (selectedCell != null && sortedAvailableCells.contains(selectedCell)) {
            val currentPosition = sortedAvailableCells.indexOf(selectedCell) + 1
            if (spinnerSelectCell.selectedItemPosition != currentPosition) {
                spinnerSelectCell.setSelection(currentPosition, false) // false to prevent immediate re-trigger
            }
        } else if (sortedAvailableCells.isNotEmpty() && spinnerSelectCell.selectedItemPosition == 0) {
            // If nothing is selected (or prompt is selected) and cells are available,
            // but don't automatically select one here, let handleHistogramsLoaded or user do it.
        } else if (sortedAvailableCells.isEmpty()) {
            spinnerSelectCell.setSelection(0)
        }
        updateArrowButtonStates()
    }

    private fun populateBssidSpinner(cell: String?) {
        val oldSelectedBssidForThisCell = if (cell == selectedCell) selectedBssid else null

        if (cell != null) {
            val bssidsInCellWithHistograms = allHistogramsByCell[cell] ?: emptyMap()
            availableBssidsDisplayInfo = bssidsInCellWithHistograms.mapNotNull { (bssidPrime, histogram) ->
                val knownAp = knownApPrimeMap[bssidPrime]
                val ssid = knownAp?.ssid ?: "N/A"
                val displayName = "$ssid ($bssidPrime)"
                BssidDisplayInfo(bssidPrime, displayName, histogram.getApproximateAverageRssi())
            }.sortedByDescending { it.averageRssi }
            availableBssidsForSelectedCell = availableBssidsDisplayInfo.map { it.bssidPrime }
        } else {
            availableBssidsDisplayInfo = emptyList()
            availableBssidsForSelectedCell = emptyList()
        }

        val displayNamesWithPrompt = listOf("Select BSSID") + availableBssidsDisplayInfo.map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayNamesWithPrompt)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSelectBssid.adapter = adapter
        spinnerSelectBssid.isEnabled = availableBssidsDisplayInfo.isNotEmpty()

        // Try to re-select the previously selected BSSID for this cell
        if (oldSelectedBssidForThisCell != null && availableBssidsForSelectedCell.contains(oldSelectedBssidForThisCell)) {
            val bssidIndexInSortedList = availableBssidsForSelectedCell.indexOf(oldSelectedBssidForThisCell)
            if (bssidIndexInSortedList != -1) {
                val currentPositionInSpinner = bssidIndexInSortedList + 1
                if (spinnerSelectBssid.selectedItemPosition != currentPositionInSpinner) {
                    spinnerSelectBssid.setSelection(currentPositionInSpinner, false)
                }
            }
        } else if (spinnerSelectBssid.selectedItemPosition != 0) {
            spinnerSelectBssid.setSelection(0) // Default to prompt if old selection invalid
        }
    }

    private fun displaySelectedHistogram() {
        if (selectedCell != null && selectedBssid != null) {
            val histogram = histogramManager.getHistogram(selectedCell!!, selectedBssid!!)
            if (histogram != null) {
                val pmf = histogram.getPmf()
                val details = StringBuilder()
                details.append("Histogram for Cell: $selectedCell, BSSID: $selectedBssid\n")
                details.append("Bin Width: ${histogram.binWidth}, Total Measurements: ${histogram.totalCount}\n\n")
                details.append("Bins (RSSI Start: Count (Probability)):\n")

                val sortedBins = histogram.bins.entries.sortedBy { it.key }
                sortedBins.forEach { (binStart, count) ->
                    val probability = pmf[binStart] ?: 0.0
                    details.append("  $binStart: $count (${String.format("%.3f", probability)})\n")
                }
                textHistogramDetails.text = details.toString()
                textHistogramStatus.text = "Displaying histogram."
                updateBarChart(histogram) // Update the chart
                barChartHistogram.visibility = View.VISIBLE
            } else {
                textHistogramDetails.text = "Histogram not found for $selectedCell / $selectedBssid."
                textHistogramStatus.text = "Histogram data missing."
                barChartHistogram.clear()
                barChartHistogram.visibility = View.GONE
            }
        } else {
            textHistogramDetails.text = ""
            barChartHistogram.clear()
            barChartHistogram.visibility = View.GONE
            if (selectedCell == null) {
                textHistogramStatus.text = "Select a cell."
            } else {
                textHistogramStatus.text = "Select a BSSID for cell $selectedCell."
            }
        }
    }

    private fun setupBarChart() {
        val typedValue = TypedValue()
        val theme = context?.theme

        // Get gridColor
        theme?.resolveAttribute(R.attr.gridColor, typedValue, true)
        val gridColor = typedValue.data

        // Get textColor
        theme?.resolveAttribute(R.attr.homeCellTextColor, typedValue, true)
        val textColor = typedValue.data

        barChartHistogram.description.isEnabled = false
        barChartHistogram.setDrawGridBackground(false)
        barChartHistogram.setDrawBarShadow(false)
        // barChartHistogram.setFitBars(true) // We will manage bar width differently for histogram look

        val xAxis = barChartHistogram.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true) // Show grid lines for bins
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.granularity = 1f // We'll set this based on bin width later
        xAxis.labelRotationAngle = -45f
        xAxis.setDrawAxisLine(true)
        xAxis.axisLineColor = textColor


        val leftAxis = barChartHistogram.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.axisMinimum = 0f
        leftAxis.textColor = textColor
        leftAxis.setDrawAxisLine(true)
        leftAxis.axisLineColor = textColor


        barChartHistogram.axisRight.isEnabled = false
        barChartHistogram.legend.isEnabled = false
        // barChartHistogram.legend.textColor = textColor // If legend were enabled

        barChartHistogram.animateY(700)
        barChartHistogram.setNoDataText("Select Cell and BSSID to view histogram.")
        barChartHistogram.setNoDataTextColor(textColor)
        barChartHistogram.isAutoScaleMinMaxEnabled = true // Important for dynamic Y-axis
        barChartHistogram.setTouchEnabled(true)
        barChartHistogram.isDragEnabled = true
        barChartHistogram.isScaleXEnabled = true
        barChartHistogram.isScaleYEnabled = true
        barChartHistogram.setPinchZoom(true)
    }

    private fun updateBarChart(histogram: Histogram) {
        val typedValue = TypedValue()
        val theme = context?.theme

        // Get gridColor
        theme?.resolveAttribute(R.attr.gridColor, typedValue, true)
        val gridColor = typedValue.data

        // Get textColor
        theme?.resolveAttribute(R.attr.homeCellTextColor, typedValue, true)
        val textColor = typedValue.data

        // Get barColor
        theme?.resolveAttribute(R.attr.cellActiveColor, typedValue, true)
        val barColor = typedValue.data

        val entries = ArrayList<BarEntry>()
        // For a histogram, the X value of the BarEntry should be the center of the bin.
        // The bar itself will then visually span the bin width.
        val sortedBins = histogram.bins.entries.sortedBy { it.key }

        if (sortedBins.isEmpty()) {
            barChartHistogram.clear()
            barChartHistogram.setNoDataText("No data for this histogram.")
            barChartHistogram.setNoDataTextColor(textColor)
            barChartHistogram.visibility = View.GONE
            return
        }

        // Prepare entries and labels
        val binLabels = ArrayList<String>()
        sortedBins.forEach { (binStart, count) ->
            // X-value is the center of the bin for plotting
            val binCenter = binStart + (histogram.binWidth / 2.0f)
            entries.add(BarEntry(binCenter, count.toFloat()))
            binLabels.add(binStart.toString()) // Store original bin start for labels
        }


        val dataSet = BarDataSet(entries, "RSSI Counts")
        dataSet.color = barColor
        dataSet.valueTextColor = textColor
        dataSet.valueTextSize = 10f
        dataSet.setDrawValues(true) // Show values on top of bars

        val barData = BarData(dataSet)
        // This makes the bars take up the full width of their "slot"
        // The slot width is determined by the x-axis granularity and range.
        // For a histogram look, we want bars to touch or be close.
        // The actual width of the bar in terms of data units is set by barWidth.
        barData.barWidth = histogram.binWidth.toFloat() * 0.9f // Make bar slightly narrower than bin for visual separation

        barChartHistogram.data = barData

        val xAxis = barChartHistogram.xAxis
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.gridColor = gridColor


        // Set X-axis min/max to encompass all bins visually
        // The first bin starts at sortedBins.first().key
        // The last bin ends at sortedBins.last().key + histogram.binWidth
        if (sortedBins.isNotEmpty()) {
            xAxis.axisMinimum = sortedBins.first().key.toFloat() - (histogram.binWidth / 2.0f) // Start slightly before first bin center
            xAxis.axisMaximum = sortedBins.last().key.toFloat() + histogram.binWidth.toFloat() + (histogram.binWidth / 2.0f) // End slightly after last bin center
            xAxis.labelCount = sortedBins.size.coerceAtMost(12) // Adjust for readability
            xAxis.granularity = histogram.binWidth.toFloat() // Grid lines at bin edges
        }


        // Custom X-axis formatter to show bin start values at appropriate positions
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                // Find the closest bin start to this axis value
                // This is tricky because 'value' is a continuous axis position.
                // We want to label the start of each bin.
                for (binStart in sortedBins.map { it.key }) {
                    if (value >= binStart && value < binStart + histogram.binWidth) {
                        return binStart.toString()
                    }
                }
                // Fallback for labels at exact bin start positions if granularity is set to binWidth
                val roundedValue = (value / histogram.binWidth).toInt() * histogram.binWidth
                if (sortedBins.any{it.key == roundedValue}) return roundedValue.toString()

                return "" // Don't label intermediate points if not a bin start
            }
        }
        // Force labels at the start of each bin if possible
        // This is complex with dynamic bin widths. A simpler approach is to ensure granularity
        // and labelCount work together. The above formatter tries to label bin starts.

        val leftAxis = barChartHistogram.axisLeft
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.gridColor = gridColor


        barChartHistogram.invalidate()
        barChartHistogram.visibility = View.VISIBLE
    }

    private fun updateSpinnersAndDisplay() {
        populateCellSpinner()
        populateBssidSpinner(selectedCell) // Will be empty if selectedCell is null
        displaySelectedHistogram()
    }

    private fun cycleCellSelection(next: Boolean) {
        if (sortedAvailableCells.isEmpty()) return

        var currentIndex = if (selectedCell != null) sortedAvailableCells.indexOf(selectedCell) else -1

        if (next) {
            currentIndex = (currentIndex + 1) % sortedAvailableCells.size
        } else {
            currentIndex = (currentIndex - 1 + sortedAvailableCells.size) % sortedAvailableCells.size
        }
        // Set spinner selection, which will trigger its onItemSelected listener
        spinnerSelectCell.setSelection(currentIndex + 1, true) // +1 for the prompt
    }
    private fun updateArrowButtonStates() {
        val enabled = sortedAvailableCells.isNotEmpty()
        buttonPrevCell.isEnabled = enabled
        buttonNextCell.isEnabled = enabled
        buttonPrevCell.alpha = if (enabled) 1.0f else 0.5f
        buttonNextCell.alpha = if (enabled) 1.0f else 0.5f
    }
}
