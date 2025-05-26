package com.example.assignment2.data.converter

import androidx.room.TypeConverter
import com.example.assignment2.data.model.MeasurementType

class MeasurementTypeConverter {
    @TypeConverter
    fun fromMeasurementType(value: MeasurementType?): String? {
        return value?.name // Converts enum to its String name (e.g., "TRAINING")
    }

    @TypeConverter
    fun toMeasurementType(value: String?): MeasurementType? {
        return value?.let {
            try {
                MeasurementType.valueOf(it) // Converts String back to enum
            } catch (e: IllegalArgumentException) {
                null // Handle cases where the string might not match an enum constant
            }
        }
    }
}