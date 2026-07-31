package com.dhanuk.photodoctorpro.utils

import android.graphics.Bitmap

/**
 * User-selectable image output format for the v2 save flow.
 */
enum class SaveFormat(val compressFormat: Bitmap.CompressFormat, val extension: String, val mimeType: String, val supportsAlpha: Boolean) {
    JPEG(Bitmap.CompressFormat.JPEG, ".jpg", "image/jpeg", false),
    PNG(Bitmap.CompressFormat.PNG, ".png", "image/png", true),
    WEBP(Bitmap.CompressFormat.WEBP, ".webp", "image/webp", true),
    WEBP_LOSSLESS(Bitmap.CompressFormat.WEBP, ".webp", "image/webp", true);

    companion object {
        /** Lossless WebP needs API 30+ (R); fall back to lossy on older devices. */
        fun defaultWebP(): SaveFormat =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) WEBP_LOSSLESS else WEBP
    }
}

/**
 * Snapshot of the user's save preferences surfaced by [SaveOptionsSheet].
 *
 * @param format       chosen output format
 * @param quality      compression quality (10..100). Ignored for PNG and lossless WebP.
 * @param bgColor      background fill (ARGB) applied when [SaveFormat.supportsAlpha] is false
 *                     and the source bitmap has transparency. Null means default white.
 * @param fileNameHint optional filename prefix override; null/blank keeps the caller default.
 */
data class SaveOptions(
    val format: SaveFormat = SaveFormat.JPEG,
    val quality: Int = 95,
    val bgColor: Int? = null,
    val fileNameHint: String? = null
) {
    init {
        require(quality in 1..100) { "quality must be 1..100, was $quality" }
    }
}
