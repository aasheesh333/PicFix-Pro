package com.dhanuk.photodoctorpro.data.repository

import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.local.HistoryDao
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {

    fun getAllHistory(): Flow<List<History>> = historyDao.getAll()

    suspend fun addHistory(history: History) {
        val latest = historyDao.getLatest()
        if (latest == null ||
            latest.operationType != history.operationType ||
            latest.filePath != history.filePath ||
            latest.inputFilePath != history.inputFilePath
        ) {
            historyDao.insert(history)
        }
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }
}
