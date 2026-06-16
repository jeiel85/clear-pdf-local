package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ImageToPdfUseCase {
    suspend fun execute(
        context: Context,
        imageUris: List<Uri>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()

            imageUris.forEachIndexed { index, uri ->
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    if (originalBitmap != null) {
                        // Create a standard A4 page size or adapt to bitmap size
                        // A4 is roughly 595 x 842 points (at 72 dpi)
                        val pageWidth = originalBitmap.width
                        val pageHeight = originalBitmap.height
                        
                        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        
                        page.canvas.drawBitmap(originalBitmap, 0f, 0f, null)
                        pdfDocument.finishPage(page)
                        
                        originalBitmap.recycle()
                    }
                }
            }

            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
