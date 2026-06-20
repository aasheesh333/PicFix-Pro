package com.dhanuk.photodoctorpro.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Wrap a Bitmap as an ImageBitmap with `remember` so that the
 * ImageBitmap wrapper is only re-created when the bitmap identity
 * changes (not on every recomposition).
 *
 * Returns null if the bitmap is null or recycled.
 */
@Composable
fun rememberBitmap(bitmap: Bitmap?): ImageBitmap? =
    remember(bitmap) { bitmap?.takeIf { !it.isRecycled }?.asImageBitmap() }