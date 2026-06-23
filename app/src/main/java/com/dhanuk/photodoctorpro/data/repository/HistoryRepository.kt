package com.dhanuk.photodoctorpro.data.repository

import android.util.Log
import com.dhanuk.photodoctorpro.BuildConfig
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.local.HistoryDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HistoryRepository private constructor(private val historyDao: HistoryDao) {

    private val insertMutex = Mutex()

    companion object {
        @Volatile
        private var INSTANCE: HistoryRepository? = null

        fun getInstance(historyDao: HistoryDao): HistoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: HistoryRepository(historyDao).also { INSTANCE = it }
            }
    }

    fun getAllHistory(): Flow<List<History>> = historyDao.getAll()

    suspend fun addHistory(history: History) = insertMutex.withLock {
        val latest = historyDao.getLatest()
        if (latest == null ||
            latest.operationType != history.operationType ||
            latest.filePath != history.filePath ||
            latest.inputFilePath != history.inputFilePath
        ) {
            historyDao.insert(history)
        } else if (BuildConfig.DEBUG) {
            Log.d("HistoryRepository", "Skipping duplicate history entry: $history")
        }
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    suspend fun deleteHistory(id: Int) {
        historyDao.deleteById(id)
    }
}
