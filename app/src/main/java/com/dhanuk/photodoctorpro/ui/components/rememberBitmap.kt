package com.dhanuk.photodoctorpro.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

@Composable
fun rememberBitmap(bitmap: Bitmap?): ImageBitmap? =
    remember(bitmap) {
        bitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
    }
