package com.example.assignment2.data.model

import com.example.assignment2.data.db.AccelMeasurement
import com.example.assignment2.data.db.ApMeasurement
import com.example.assignment2.data.db.KnownAp
import com.example.assignment2.data.db.KnownApPrime
import com.example.assignment2.data.db.MeasurementTime

data class DatabaseBackup(
    val knownAps: List<KnownAp>,
    val knownApPrimes: List<KnownApPrime>,
    val measurementTimes: List<MeasurementTime>,
    val apMeasurements: List<ApMeasurement>,
    val accelMeasurements: List<AccelMeasurement>
)