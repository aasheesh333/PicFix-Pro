package com.dhanuk.photodoctorpro.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(history: History)

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<History>>

    @Query("DELETE FROM history")
    suspend fun clearAll()

    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): History?

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Int)
}
