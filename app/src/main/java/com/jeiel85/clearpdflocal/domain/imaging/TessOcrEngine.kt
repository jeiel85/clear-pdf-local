package com.jeiel85.clearpdflocal.domain.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * On-device OCR via Tesseract (tesseract4android). Fully offline: the Korean + English
 * trained data is bundled in assets and copied to the app's private storage on first use —
 * nothing is ever downloaded, keeping the no-INTERNET brand intact.
 */
object TessOcrEngine {

    private const val LANG = "kor+eng"
    private val TRAINEDDATA = listOf("kor.traineddata", "eng.traineddata")

    /** A recognized word and its bounding box, in source-image pixel coordinates. */
    data class OcrWord(val text: String, val box: Rect)

    /**
     * Copies the bundled traineddata into filesDir/tess/tessdata on first run and returns the
     * data path (the parent of the `tessdata` folder) that TessBaseAPI.init expects.
     */
    private fun ensureData(context: Context): String {
        val dataDir = File(context.filesDir, "tess")
        val tessdata = File(dataDir, "tessdata")
        if (!tessdata.exists()) tessdata.mkdirs()
        for (name in TRAINEDDATA) {
            val out = File(tessdata, name)
            if (!out.exists() || out.length() == 0L) {
                context.assets.open("tessdata/$name").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
        return dataDir.absolutePath
    }

    /**
     * Recognizes words (with bounding boxes) in [bitmap]. Returns an empty list on any failure
     * so callers can degrade gracefully to an image-only PDF.
     */
    fun recognizeWords(context: Context, bitmap: Bitmap): List<OcrWord> {
        val dataPath = try {
            ensureData(context)
        } catch (e: Exception) {
            Log.e("TessOcrEngine", "Failed to stage traineddata", e)
            return emptyList()
        }

        val tess = TessBaseAPI()
        return try {
            if (!tess.init(dataPath, LANG)) {
                Log.e("TessOcrEngine", "TessBaseAPI.init failed")
                return emptyList()
            }
            tess.setImage(bitmap)
            tess.getUTF8Text() // forces recognition before iterating

            val words = ArrayList<OcrWord>()
            val iterator = tess.resultIterator ?: return words
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!text.isNullOrBlank()) {
                    val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (box != null && box.width() > 0 && box.height() > 0) {
                        words.add(OcrWord(text, box))
                    }
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            iterator.delete()
            words
        } catch (e: Exception) {
            Log.e("TessOcrEngine", "OCR failed", e)
            emptyList()
        } finally {
            try {
                tess.recycle()
            } catch (_: Exception) {
            }
        }
    }
}
