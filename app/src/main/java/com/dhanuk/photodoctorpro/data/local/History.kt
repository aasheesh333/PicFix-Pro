package com.dhanuk.photodoctorpro.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class History(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val operationType: String,
    val inputFilePath: String,
    val filePath: String,
    val timestamp: Long
)
