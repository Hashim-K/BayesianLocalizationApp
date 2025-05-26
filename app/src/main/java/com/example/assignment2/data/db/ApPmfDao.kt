package com.example.assignment2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ApPmfDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE) // REPLACE if (BSSID, cell, binWidth) combo exists
    suspend fun insertOrReplace(apPmf: ApPmf)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(apPmfs: List<ApPmf>)

    @Update
    suspend fun update(apPmf: ApPmf) // If you need to update, ensure PK is matched

    // Query by the composite primary key
    @Query("SELECT * FROM ap_pmf WHERE BSSID = :bssidPrime AND cell = :cell AND binWidth = :binWidth LIMIT 1")
    suspend fun getPmf(bssidPrime: String, cell: String, binWidth: Int): ApPmf?

    @Query("SELECT * FROM ap_pmf WHERE BSSID = :bssidPrime AND cell = :cell AND binWidth = :binWidth LIMIT 1")
    fun getPmfFlow(bssidPrime: String, cell: String, binWidth: Int): Flow<ApPmf?>

    // Get all PMFs for a given BSSID_prime and cell (could be multiple if different binWidths)
    @Query("SELECT * FROM ap_pmf WHERE BSSID = :bssidPrime AND cell = :cell")
    suspend fun getPmfsForBssidAndCell(bssidPrime: String, cell: String): List<ApPmf>

    @Query("SELECT * FROM ap_pmf WHERE BSSID = :bssidPrime")
    suspend fun getPmfsForBssid(bssidPrime: String): List<ApPmf>

    @Query("SELECT * FROM ap_pmf")
    suspend fun getAllPmfs(): List<ApPmf>

    @Query("DELETE FROM ap_pmf WHERE BSSID = :bssidPrime AND cell = :cell AND binWidth = :binWidth")
    suspend fun delete(bssidPrime: String, cell: String, binWidth: Int)

    @Query("DELETE FROM ap_pmf")
    suspend fun clearTable()

    @Query("SELECT * FROM ap_pmf WHERE BSSID = :bssidPrime AND binWidth = :binWidth")
    suspend fun getPmfsForBssidAndBinWidth(bssidPrime: String, binWidth: Int): List<ApPmf> // Returns list of PMFs for this AP for each cell
}