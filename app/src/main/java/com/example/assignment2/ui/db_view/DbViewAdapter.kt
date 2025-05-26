package com.example.assignment2.ui.db_view

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.assignment2.R
import com.example.assignment2.data.db.AccelMeasurement
import com.example.assignment2.data.db.KnownAp
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.data.db.OuiManufacturer
import com.example.assignment2.data.model.MeasurementTimeViewItem // Import new data class
import com.example.assignment2.data.model.MeasurementViewItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DbViewAdapter : RecyclerView.Adapter<DbViewAdapter.DbViewHolder>() {

    private var items: List<Any> = emptyList()

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    class DbViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val col1: TextView = itemView.findViewById(R.id.text_col1)
        val col2: TextView = itemView.findViewById(R.id.text_col2)
        val col3: TextView = itemView.findViewById(R.id.text_col3)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DbViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_db_row, parent, false)
        return DbViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: DbViewHolder, position: Int) {
        val item = items[position]

        when (item) {
            is KnownAp -> {
                holder.col1.text = item.bssid
                holder.col2.text = item.ssid ?: "(N/A)"
                holder.col3.text = item.apType.name
            }
            is KnownApPrime -> {
                holder.col1.text = item.bssidPrime
                holder.col2.text = item.ssid ?: "(N/A)"
                holder.col3.text = item.apType.name
            }
            is MeasurementViewItem -> {
                holder.col1.text = item.bssidPrime // Display bssid_prime
                holder.col2.text = "${dateFormatter.format(Date(item.timestampMillis))} [${item.cell}] (${item.measurementType.name})"
                holder.col3.text = "${item.rssi} dBm (SSID: ${item.ssid ?: "N/A"})"
            }
            is MeasurementTimeViewItem -> { // New: For Measurement Times overview
                holder.col1.text = dateFormatter.format(Date(item.timestampMillis))
                holder.col2.text = item.cell
                holder.col3.text = item.measurementType?.name ?: "N/A" // Display type
            }
            is AccelMeasurement -> {
                holder.col1.text = dateFormatter.format(Date(item.timestampMillis))
                holder.col2.text = item.activityType
                holder.col3.text = String.format("X:%.1f Y:%.1f Z:%.1f", item.xMaxMin, item.yMaxMin, item.zMaxMin)
            }
            is OuiManufacturer -> {
                holder.col1.text = item.oui
                holder.col2.text = item.shortName ?: "(N/A)"
                holder.col3.text = item.fullName
            }
            else -> {
                holder.col1.text = "Error"
                holder.col2.text = "Invalid"
                holder.col3.text = "Data"
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }
}
