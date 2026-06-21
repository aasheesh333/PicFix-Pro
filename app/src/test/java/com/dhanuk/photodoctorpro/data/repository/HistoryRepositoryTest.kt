package com.dhanuk.photodoctorpro.data.repository

import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.local.HistoryDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRepositoryTest {

    private val dao = mockk<HistoryDao>(relaxed = true)

    @Test
    fun `getAllHistory delegates to dao`() = runTest {
        val expected = listOf(
            History(operationType = "Enhance", inputFilePath = "a", filePath = "b", timestamp = 1L)
        )
        coEvery { dao.getAll() } returns flowOf(expected)

        val repo = HistoryRepository(dao)
        val flow = repo.getAllHistory()

        flow.collect { actual ->
            assertEquals(expected, actual)
        }
        coVerify(exactly = 1) { dao.getAll() }
    }

    @Test
    fun `addHistory inserts when latest entry differs`() = runTest {
        coEvery { dao.getLatest() } returns null
        val repo = HistoryRepository(dao)
        val entry = History(operationType = "Erase", inputFilePath = "i", filePath = "o", timestamp = 0L)

        repo.addHistory(entry)

        coVerify(exactly = 1) { dao.insert(entry) }
    }

    @Test
    fun `addHistory skips exact duplicate of latest`() = runTest {
        val latest = History(id = 1, operationType = "Erase", inputFilePath = "i", filePath = "o", timestamp = 0L)
        coEvery { dao.getLatest() } returns latest
        val repo = HistoryRepository(dao)
        val dup = latest.copy(timestamp = 999L)

        repo.addHistory(dup)

        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `addHistory inserts when only timestamp differs`() = runTest {
        val latest = History(id = 1, operationType = "Erase", inputFilePath = "i", filePath = "o", timestamp = 0L)
        coEvery { dao.getLatest() } returns latest
        val repo = HistoryRepository(dao)
        val different = latest.copy(operationType = "Enhance", timestamp = 999L)

        repo.addHistory(different)

        coVerify(exactly = 1) { dao.insert(different) }
    }

    @Test
    fun `clearHistory delegates to dao`() = runTest {
        val repo = HistoryRepository(dao)
        repo.clearHistory()
        coVerify(exactly = 1) { dao.clearAll() }
    }

    @Test
    fun `deleteHistory delegates to dao by id`() = runTest {
        val repo = HistoryRepository(dao)
        repo.deleteHistory(42)
        coVerify(exactly = 1) { dao.deleteById(42) }
    }
}
