package com.dhanuk.photodoctorpro.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Wraps a [Bitmap] as a Compose [ImageBitmap] without an extra pixel copy.
 *
 * The previous implementation called [Bitmap.copy] on every new reference, which
 * doubled peak memory for large images. We now wrap the bitmap directly; the
 * caller (ViewModel) owns the bitmap lifecycle and must keep it alive while the
 * Composable is using it.
 */
@Composable
fun rememberBitmap(bitmap: Bitmap?): ImageBitmap? =
    remember(bitmap) {
        bitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
