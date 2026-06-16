package com.example.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.manager.FileAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object MergePdfUseCase {
    suspend fun execute(
        context: Context,
        pdfUris: List<Uri>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val pdfDocument = PdfDocument()
            var pageIndex = 1

            pdfUris.forEach { uri ->
                // Copy uri to seekable file to safe-access via ParcelFileDescriptor
                val tempName = "merge_temp_${System.currentTimeMillis()}.pdf"
                val tempFile = FileAccessManager.copyToInternalCache(context, uri, tempName)
                if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                    val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    
                    for (i in 0 until renderer.pageCount) {
                        val srcPage = renderer.openPage(i)
                        
                        // Render at 2x resolution to keep text crisp and clear
                        val scale = 2.0f
                        val renderW = (srcPage.width * scale).toInt()
                        val renderH = (srcPage.height * scale).toInt()
                        
                        val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE) // PDF backgrounds are transparent by default, color white
                        
                        srcPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        
                        val pageInfo = PdfDocument.PageInfo.Builder(srcPage.width, srcPage.height, pageIndex++).create()
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
