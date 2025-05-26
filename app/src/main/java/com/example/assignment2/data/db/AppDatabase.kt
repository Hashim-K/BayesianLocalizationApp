package com.example.assignment2.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.assignment2.data.converter.ApTypeConverter
import com.example.assignment2.data.db.converter.HistogramBinsConverter
import com.example.assignment2.data.converter.MeasurementTypeConverter
import com.example.assignment2.data.model.ApType
import com.example.assignment2.data.model.MeasurementType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@Database(
    entities = [
        KnownAp::class, ApMeasurement::class, MeasurementTime::class,
        AccelMeasurement::class, AccelTestData::class, ApPmf::class,
        OuiManufacturer::class, KnownApPrime::class
    ],
    version = 13, // Increment version from 12 to 13
    exportSchema = true
)
@TypeConverters(
    MeasurementTypeConverter::class,
    ApTypeConverter::class,
    HistogramBinsConverter::class
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun knownApDao(): KnownApDao
    abstract fun apMeasurementDao(): ApMeasurementDao
    abstract fun measurementTimeDao(): MeasurementTimeDao
    abstract fun accelMeasurementDao(): AccelMeasurementDao
    abstract fun accelTestDataDao(): AccelTestDataDao
    abstract fun apPmfDao(): ApPmfDao
    abstract fun ouiManufacturerDao(): OuiManufacturerDao
    abstract fun knownApPrimeDao(): KnownApPrimeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Migration from version 4 to 5 (for ApMeasurement.measurement_type)
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ap_measurements ADD COLUMN measurement_type TEXT NOT NULL DEFAULT '${MeasurementType.TRAINING.name}'"
                )
            }
        }

        // Migration from version 5 to 6 (for creating ap_pmf table)
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val rssiColumns = (-100..0).joinToString(separator = ",\n") { i ->
                    "    `rssi_${kotlin.math.abs(i)}` REAL"
                }
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ap_pmf` (
                        `BSSID` TEXT NOT NULL,
                        `cell` TEXT NOT NULL,
                    $rssiColumns,
                        PRIMARY KEY(`BSSID`, `cell`),
                        FOREIGN KEY(`BSSID`) REFERENCES `known_aps`(`bssid`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ap_pmf_BSSID` ON `ap_pmf` (`BSSID`)")
            }
        }

        // Migration from version 6 to 7: Add ap_type to known_aps
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add the new 'ap_type' column to the 'known_aps' table.
                // Since 'apType' in KnownAp is non-nullable,
                // we must provide a non-null DEFAULT value for existing rows.
                // Defaulting to 'FIXED' seems like a reasonable assumption for existing APs.
                db.execSQL(
                    "ALTER TABLE known_aps ADD COLUMN ap_type TEXT NOT NULL DEFAULT '${ApType.FIXED.name}'"
                )
            }
        }

        // Migration from version 7 to 8: Create oui_manufacturers table
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `oui_manufacturers` (
                        `oui` TEXT NOT NULL,
                        `short_name` TEXT,
                        `full_name` TEXT NOT NULL,
                        PRIMARY KEY(`oui`)
                    )
                """.trimIndent())
            }
        }

        // Migration from version 8 to 9: Move measurement_type
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add measurement_type to measurement_times
                db.execSQL(
                    "ALTER TABLE measurement_times ADD COLUMN measurement_type TEXT NOT NULL DEFAULT '${MeasurementType.TRAINING.name}'"
                )

                // 2. Populate new measurement_times.measurement_type from ap_measurements
                // This assumes that for a given timestampId, all ap_measurements had the same type.
                // If not, it picks one.
                db.execSQL("""
                    UPDATE measurement_times
                    SET measurement_type = (
                        SELECT am.measurement_type
                        FROM ap_measurements AS am
                        WHERE am.timestampId = measurement_times.timestampId
                        LIMIT 1
                    )
                    WHERE EXISTS (
                        SELECT 1
                        FROM ap_measurements AS am
                        WHERE am.timestampId = measurement_times.timestampId
                        LIMIT 1
                    )
                """.trimIndent())

                // 3. Recreate ap_measurements table without measurement_type column
                // (Standard Room procedure for removing a column)
                db.execSQL("""
                    CREATE TABLE ap_measurements_new (
                        bssid TEXT NOT NULL,
                        timestampId INTEGER NOT NULL,
                        rssi INTEGER NOT NULL,
                        PRIMARY KEY(bssid, timestampId),
                        FOREIGN KEY(bssid) REFERENCES known_aps(bssid) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(timestampId) REFERENCES measurement_times(timestampId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                // Copy data
                db.execSQL("""
                    INSERT INTO ap_measurements_new (bssid, timestampId, rssi)
                    SELECT bssid, timestampId, rssi FROM ap_measurements
                """.trimIndent())
                // Drop old table
                db.execSQL("DROP TABLE ap_measurements")
                // Rename new table
                db.execSQL("ALTER TABLE ap_measurements_new RENAME TO ap_measurements")
                // Recreate indices (if you had custom ones beyond PK/FK, add them here)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ap_measurements_bssid ON ap_measurements (bssid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ap_measurements_timestampId ON ap_measurements (timestampId)")
            }
        }

        // Migration from version 9 to 10: Create known_ap_prime and populate
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create known_ap_prime table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `known_ap_prime` (
                        `bssid_prime` TEXT NOT NULL,
                        `ssid` TEXT,
                        `ap_type` TEXT NOT NULL,
                        PRIMARY KEY(`bssid_prime`)
                    )
                """.trimIndent())

                // 2. Populate known_ap_prime from known_aps
                // This is tricky due to potential duplicates mapping to the same prime.
                // We'll iterate and use INSERT OR REPLACE, so the last one processed wins for a given prime.
                // This requires fetching data, processing in Kotlin, then inserting.
                // For a pure SQL migration, it's harder to apply the BssidUtil logic directly
                // and handle conflicts gracefully for ssid/apType.

                // Note: Performing this data migration within the `migrate` function
                // by querying the old table and inserting into the new one is complex
                // because DAO access isn't directly available here.
                // A common approach for complex data transformation during migration:
                // a) Create the new table.
                // b) After the database is built (e.g., in a callback or on first access),
                //    run a one-time Kotlin coroutine to read from known_aps, transform,
                //    and insert into known_ap_prime.

                // For simplicity in this migration step, we'll just create the table.
                // Population will be handled by the app logic when it next processes APs,
                // or you can add a one-time population step in AppDatabaseCallback.onOpen
                // if the known_ap_prime table is empty.
                android.util.Log.i("MIGRATION_9_10", "known_ap_prime table created. Population will occur via app logic or a separate one-time task.")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create the new ap_measurements table structure
                db.execSQL("""
                    CREATE TABLE ap_measurements_new (
                        `bssid_prime` TEXT NOT NULL,
                        `timestampId` INTEGER NOT NULL,
                        `rssi` INTEGER NOT NULL,
                        PRIMARY KEY(`bssid_prime`, `timestampId`),
                        FOREIGN KEY(`bssid_prime`) REFERENCES `known_ap_prime`(`bssid_prime`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`timestampId`) REFERENCES `measurement_times`(`timestampId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ap_measurements_new_bssid_prime ON ap_measurements_new (bssid_prime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ap_measurements_new_timestampId ON ap_measurements_new (timestampId)")

                // 2. Populate the new table from the old one, transforming bssid to bssid_prime
                //    and taking the MAX(rssi) for duplicate (bssid_prime, timestampId) pairs.
                //    The SQLite SUBSTR function is 1-indexed.
                //    REPLACE(UPPER(bssid), ':', '') creates the 12-char hex string.
                //    SUBSTR(..., 1, 11) takes the first 11 chars.
                //    Then we append '0'.
                db.execSQL("""
                    INSERT INTO ap_measurements_new (bssid_prime, timestampId, rssi)
                    SELECT
                        SUBSTR(REPLACE(UPPER(am_old.bssid), ':', ''), 1, 11) || '0' AS calculated_bssid_prime,
                        am_old.timestampId,
                        MAX(am_old.rssi) AS max_rssi
                    FROM ap_measurements AS am_old  -- Use alias for clarity
                    GROUP BY calculated_bssid_prime, am_old.timestampId
                """.trimIndent())

                // 3. Drop the old ap_measurements table
                db.execSQL("DROP TABLE ap_measurements")

                // 4. Rename the new table to ap_measurements
                db.execSQL("ALTER TABLE ap_measurements_new RENAME TO ap_measurements")

                android.util.Log.i("MIGRATION_10_11", "ap_measurements table restructured for bssid_prime and data migrated.")
            }
        }

        // Migration for ApPmf table restructure
        val MIGRATION_11_12 = object : Migration(11, 12) { // Adjust versions as needed
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Drop the old ap_pmf table
                db.execSQL("DROP TABLE IF EXISTS ap_pmf")
                android.util.Log.i("MIGRATION_10_11", "Old ap_pmf table dropped.")

                // 2. Create the new ap_pmf table with the new schema
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ap_pmf` (
                        `BSSID` TEXT NOT NULL, -- This will store bssid_prime
                        `cell` TEXT NOT NULL,
                        `binWidth` INTEGER NOT NULL,
                        `bins_data` TEXT NOT NULL, -- Stores JSON of Map<Int, Int>
                        PRIMARY KEY(`BSSID`, `cell`, `binWidth`),
                        FOREIGN KEY(`BSSID`) REFERENCES `known_ap_prime`(`bssid_prime`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ap_pmf_BSSID` ON `ap_pmf` (`BSSID`)")
                android.util.Log.i("MIGRATION_10_11", "New ap_pmf table created.")
                // Data from the old 101-column ap_pmf table is NOT migrated here due to complexity.
                // New PMFs will need to be generated and saved.
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns with default values
                db.execSQL("ALTER TABLE ap_pmf ADD COLUMN min_rssi INTEGER NOT NULL DEFAULT -100")
                db.execSQL("ALTER TABLE ap_pmf ADD COLUMN max_rssi INTEGER NOT NULL DEFAULT 0")
                android.util.Log.i("MIGRATION_11_12", "ApPmf table updated with min_rssi and max_rssi columns.")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wifi_scan_database"
                )
                    .addCallback(AppDatabaseCallback(context))
                    .addMigrations(
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    // Callback to populate OUI data from manuf.txt asset
    private class AppDatabaseCallback(private val context: Context) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateOuiData(context, database.ouiManufacturerDao())
                }
            }
        }

        // Optional: If you want to re-populate if the table is empty on app start (not just creation)
         override fun onOpen(db: SupportSQLiteDatabase) {
             super.onOpen(db)
             INSTANCE?.let { database ->
                 CoroutineScope(Dispatchers.IO).launch {
                     if (database.ouiManufacturerDao().getCount() == 0) {
                         populateOuiData(context, database.ouiManufacturerDao())
                     }
                 }
             }
         }

        suspend fun populateOuiData(context: Context, ouiDao: OuiManufacturerDao) {
            try {
                val manufacturers = mutableListOf<OuiManufacturer>()
                context.assets.open("manuf.txt").use { inputStream -> // Ensure manuf.txt is in app/src/main/assets
                    BufferedReader(InputStreamReader(inputStream)).forEachLine { line ->
                        if (line.isNotBlank() && !line.startsWith("#")) {
                            val parts = line.split('\t', limit = 3) // Split by tab, max 3 parts
                            if (parts.size >= 2) { // Need at least OUI and short/full name
                                val ouiRaw = parts[0].trim()
                                // We are interested in the standard 6-hex-digit OUI prefix
                                if (ouiRaw.length == 8 && ouiRaw.count { it == ':' } == 2) { // e.g., 00:00:0C
                                    val ouiNormalized = ouiRaw.replace(":", "").uppercase()
                                    val shortName = if (parts.size > 1) parts[1].trim() else null
                                    val fullName = if (parts.size > 2) parts[2].trim() else shortName ?: "Unknown" // Use short name if full is missing

                                    if (ouiNormalized.length == 6) { // Ensure it's a 6-char OUI after normalization
                                        manufacturers.add(
                                            OuiManufacturer(
                                                oui = ouiNormalized,
                                                shortName = shortName,
                                                fullName = fullName
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (manufacturers.isNotEmpty()) {
                    ouiDao.insertAll(manufacturers)
                    android.util.Log.i("AppDatabaseCallback", "Populated ${manufacturers.size} OUI manufacturers.")
                } else {
                    android.util.Log.w("AppDatabaseCallback", "No OUI manufacturers found in manuf.txt or file format issue.")
                }
            } catch (e: Exception) {
                android.util.Log.e("AppDatabaseCallback", "Error populating OUI data", e)
            }
        }
    }
}