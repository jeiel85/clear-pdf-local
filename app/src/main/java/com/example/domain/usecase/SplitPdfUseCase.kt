package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.manager.FileAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object SplitPdfUseCase {
    suspend fun execute(
        context: Context,
        pdfUri: Uri,
        rangeString: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pageIndexes = parseRangeString(rangeString)
            if (pageIndexes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No valid pages specified in range."))
            }

            val tempName = "split_temp_${System.currentTimeMillis()}.pdf"
            val tempFile = FileAccessManager.copyToInternalCache(context, pdfUri, tempName)
            if (tempFile == null || !tempFile.exists() || tempFile.length() <= 0) {
                return@withContext Result.failure(Exception("Could not open source PDF file."))
            }

            val pdfDocument = PdfDocument()
            var destinationPageNumber = 1

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val totalPages = renderer.pageCount

            // Convert human 1-indexed list to 0-indexed renderer indices
            val targetPages0Indexed = pageIndexes.map { it - 1 }.filter { it in 0 until totalPages }

            if (targetPages0Indexed.isEmpty()) {
                renderer.close()
                pfd.close()
                tempFile.delete()
                return@withContext Result.failure(IllegalArgumentException("All requested pages are out of file range. Max page is $totalPages."))
            }

            targetPages0Indexed.forEach { idx ->
                val srcPage = renderer.openPage(idx)
                
                // Render at high resolution
                val scale = 2.0f
                val renderW = (srcPage.width * scale).toInt()
                val renderH = (srcPage.height * scale).toInt()
                
                val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                
                srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val pageInfo = PdfDocument.PageInfo.Builder(srcPage.width, srcPage.height, destinationPageNumber++).create()
                val destPage = pdfDocument.startPage(pageInfo)
                
                val canvas = destPage.canvas
                val destRect = android.graphics.Rect(0, 0, srcPage.width, srcPage.height)
                canvas.drawBitmap(bitmap, null, destRect, null)
                
                pdfDocument.finishPage(destPage)
                
                srcPage.close()
                bitmap.recycle()
            }

            renderer.close()
            pfd.close()
            tempFile.delete()

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses a page ranges string such as "1-3, 5, 8-10" into a list of specific page numbers.
     */
    fun parseRangeString(rangeStr: String): List<Int> {
        val pages = mutableListOf<Int>()
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.contains("-")) {
                val rangeParts = trimmed.split("-")
                if (rangeParts.size >= 2) {
                    val startStr = rangeParts[0].trim()
                    val endStr = rangeParts[1].trim()
                    val start = startStr.toIntOrNull()
                    val end = endStr.toIntOrNull()
                    if (start != null && end != null) {
                        val min = start.coerceAtMost(end)
                        val max = start.coerceAtLeast(end)
                        for (i in min..max) {
                            pages.add(i)
                        }
                    }
                }
            } else {
                val page = trimmed.toIntOrNull()
                if (page != null) {
                    pages.add(page)
                }
            }
        }
        return pages.distinct()
    }
}
