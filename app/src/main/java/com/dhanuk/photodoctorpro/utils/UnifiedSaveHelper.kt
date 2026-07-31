package com.dhanuk.photodoctorpro.utils

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.dhanuk.photodoctorpro.data.local.History
import com.dhanuk.photodoctorpro.data.repository.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UnifiedSaveHelper {

    suspend fun saveAndRecord(
        activity: Activity,
        bitmap: Bitmap,
        fileNamePrefix: String,
        operationType: String,
        inputUriString: String,
        repository: HistoryRepository,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        showAd: Boolean = true
    ): String = saveAndRecord(
        activity, bitmap, fileNamePrefix, operationType, inputUriString, repository,
        SaveOptions(format = SaveFormat.entries.first { it.compressFormat == format }, quality = 95, bgColor = null),
        showAd
    )

    suspend fun saveAndRecord(
        activity: Activity,
        bitmap: Bitmap,
        fileNamePrefix: String,
        operationType: String,
        inputUriString: String,
        repository: HistoryRepository,
        options: SaveOptions,
        showAd: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val fileName = "${fileNamePrefix}_${System.currentTimeMillis()}"
        val filePath = BitmapUtils.saveBitmap(activity, bitmap, fileName, options)
        repository.addHistory(
            History(
                operationType = operationType,
                inputFilePath = inputUriString,
                filePath = filePath,
                timestamp = System.currentTimeMillis()
            )
        )
        filePath
    }

    suspend fun saveAndRecordNoAd(
        context: Context,
        bitmap: Bitmap,
        fileNamePrefix: String,
        operationType: String,
        inputUriString: String,
        repository: HistoryRepository,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG
    ): String = saveAndRecordNoAd(
        context, bitmap, fileNamePrefix, operationType, inputUriString, repository,
        SaveOptions(format = SaveFormat.entries.first { it.compressFormat == format }, quality = 95, bgColor = null)
    )

    suspend fun saveAndRecordNoAd(
        context: Context,
        bitmap: Bitmap,
        fileNamePrefix: String,
        operationType: String,
        inputUriString: String,
        repository: HistoryRepository,
        options: SaveOptions
    ): String = withContext(Dispatchers.IO) {
        val fileName = "${fileNamePrefix}_${System.currentTimeMillis()}"
        val filePath = BitmapUtils.saveBitmap(context, bitmap, fileName, options)
        repository.addHistory(
            History(
                operationType = operationType,
                inputFilePath = inputUriString,
                filePath = filePath,
                timestamp = System.currentTimeMillis()
            )
        )
        filePath
    }
}