package com.jeiel85.clearpdflocal.data.manager

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileAccessManager {
    private const val TAG = "FileAccessManager"

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unnamed.pdf"
        try {
            val contentResolver: ContentResolver = context.contentResolver
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file name from uri", e)
            name = uri.lastPathSegment ?: name
        }
        return name
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = 0
        try {
            val contentResolver: ContentResolver = context.contentResolver
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size from uri", e)
        }
        return size
    }

    fun takePersistableUriPermission(context: Context, uri: Uri) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Could not take persistable permission for uri (often normal for non-SAF uris)", e)
        }
    }

    /**
     * Copies a Content Uri content into internal cache directory so it can be reliably operated on by PdfRenderer.
     * PdfRenderer requires a seekable FileDescriptor, which Uri stream doesn't always support cleanly.
     */
    fun copyToInternalCache(context: Context, uri: Uri, tempName: String): File? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val cacheFile = File(context.cacheDir, tempName)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            if (inputStream != null) {
                FileOutputStream(cacheFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                cacheFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fail copying stream to cache", e)
            null
        }
    }
}
