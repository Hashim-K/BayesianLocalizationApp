package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.assignment2.data.model.ActivityCount

@Dao
interface AccelTestDataDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(accelTestData: AccelTestData)

    @Query("SELECT * FROM accel_test_data ORDER BY timestampMillis DESC")
    suspend fun getAllTestData(): List<AccelTestData>

    // Count samples for a specific activity type in the test set
    @Query("SELECT COUNT(*) FROM accel_test_data WHERE actualActivityType = :activityType")
    suspend fun getCountForActivity(activityType: String): Int

    // Get counts for all activities (useful for initial load)
    @Query("SELECT actualActivityType, COUNT(*) AS count FROM accel_test_data GROUP BY actualActivityType")
    suspend fun getAllActivityCounts(): List<ActivityCount>

    @Query("DELETE FROM accel_test_data")
    suspend fun clearTable()
}