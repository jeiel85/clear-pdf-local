package com.jeiel85.clearpdflocal.domain.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Bitmap decode/encode helpers for the scan pipeline. Full-resolution camera photos (12 MP+)
 * would blow up OpenCV Mat memory, so raw frames are decoded down to a sane working size that
 * is still comfortably sharp for documents.
 */
object BitmapIo {

    /** Longest-edge cap for processing; plenty of detail for readable document scans. */
    private const val MAX_DIM = 2500

    /** Decodes [path] downsampled so its longest side is <= [maxDim]. */
    fun decodeScaled(path: String, maxDim: Int = MAX_DIM): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** Writes [bitmap] to [file] as JPEG; returns the file on success. */
    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 92): File {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        return file
    }
}
