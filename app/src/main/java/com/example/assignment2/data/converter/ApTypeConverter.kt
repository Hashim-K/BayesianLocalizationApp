package com.example.assignment2.data.converter

import androidx.room.TypeConverter
import com.example.assignment2.data.model.ApType

class ApTypeConverter {
    @TypeConverter
    fun fromApType(value: ApType?): String? {
        return value?.name // Converts enum to its String name (e.g., "FIXED")
    }

    @TypeConverter
    fun toApType(value: String?): ApType? {
        return value?.let {
            try {
                ApType.valueOf(it) // Converts String back to enum
            } catch (e: IllegalArgumentException) {
                null // Handle cases where the string might not match an enum constant
            }
        }
    }
}
