package com.example.assignment2.data.db.converter

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistogramBinsConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromBinsMap(bins: Map<Int, Int>?): String? {
        if (bins == null) {
            return null
        }
        return gson.toJson(bins)
    }

    @TypeConverter
    fun toBinsMap(binsJson: String?): Map<Int, Int>? {
        if (binsJson == null) {
            return null
        }
        val type = object : TypeToken<Map<Int, Int>>() {}.type
        return gson.fromJson(binsJson, type)
    }
}