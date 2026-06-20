package com.jeiel85.clearpdflocal.domain.usecase

import android.content.Context
import com.jeiel85.clearpdflocal.domain.imaging.BitmapIo
import com.jeiel85.clearpdflocal.domain.imaging.TessOcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a searchable PDF: each page draws the scanned image and lays the OCR-recognized words
 * over it as an invisible (render mode 3) text layer, so the text can be selected and searched.
 * All on-device — Tesseract OCR + a bundled Korean/Latin font, no network.
 *
 * The text layer is best-effort: any word the embedded font cannot encode is skipped so the
 * PDF (image + whatever text encoded) always saves successfully.
 */
object SearchablePdfUseCase {

    /** Longest edge used for both the PDF page image and OCR — same bitmap, so boxes align 1:1. */
    private const val MAX_DIM = 3000

    suspend fun execute(
        context: Context,
        imagePaths: List<String>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { doc ->
                val font = context.assets.open("fonts/NanumGothic-Regular.ttf").use {
                    PDType0Font.load(doc, it, true) // embedSubset = true → only used glyphs are stored
                }

                for (path in imagePaths) {
                    val bitmap = BitmapIo.decodeScaled(path, MAX_DIM) ?: continue
                    val w = bitmap.width.toFloat()
                    val h = bitmap.height.toFloat()

                    val page = PDPage(PDRectangle(w, h))
                    doc.addPage(page)
                    val image = JPEGFactory.createFromImage(doc, bitmap, 0.8f)

                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(image, 0f, 0f, w, h)

                        val words = TessOcrEngine.recognizeWords(context, bitmap)
                        for (word in words) {
                            val box = word.box
                            val fontSize = box.height().toFloat().coerceIn(1f, h)
                            try {
                                cs.beginText()
                                cs.setRenderingMode(RenderingMode.NEITHER) // invisible text layer
                                cs.setFont(font, fontSize)
                                // PDF origin is bottom-left; image/Tesseract is top-left → flip Y.
                                cs.newLineAtOffset(box.left.toFloat(), h - box.bottom.toFloat())
                                cs.showText(word.text)
                                cs.endText()
                            } catch (e: Exception) {
                                // Font can't encode a glyph (or similar) — skip the word, keep going.
                                try { cs.endText() } catch (_: Exception) {}
                            }
                        }
                    }
                    bitmap.recycle()
                }

                FileOutputStream(outputFile).use { doc.save(it) }
            }
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
