package com.jeiel85.clearpdflocal.domain.imaging

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Best-effort finger removal: masks skin-coloured regions that enter from a page edge (where a
 * thumb holding a book usually is) and inpaints them away.
 *
 * Honest limits: skin-colour detection can misfire on warm-toned documents, and inpainting fills
 * the area with plausible background — it cannot reconstruct text that was hidden under the
 * finger. Returns null when no finger-like region is found (caller keeps the original).
 */
object FingerRemover {

    /** A skin blob must cover at least this fraction of the image to be treated as a finger. */
    private const val MIN_BLOB_AREA_RATIO = 0.004

    fun removeFingers(src: Bitmap): Bitmap? {
        if (!OpenCvInitializer.ensureInitialized()) return null

        val rgba = Mat()
        val rgb = Mat()
        val ycrcb = Mat()
        val skin = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val fingerMask = Mat()
        val tmp = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, ycrcb, Imgproc.COLOR_RGB2YCrCb)

            // Skin range in YCrCb (common heuristic): Cr 133-173, Cb 77-127.
            Core.inRange(ycrcb, Scalar(0.0, 133.0, 77.0), Scalar(255.0, 173.0, 127.0), skin)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_OPEN, k)
            Imgproc.morphologyEx(skin, skin, Imgproc.MORPH_CLOSE, k)
            k.release()

            val w = skin.width()
            val h = skin.height()
            val minArea = w * h * MIN_BLOB_AREA_RATIO
            val labelCount = Imgproc.connectedComponentsWithStats(skin, labels, stats, centroids)

            fingerMask.create(h, w, CvType.CV_8UC1)
            fingerMask.setTo(Scalar(0.0))
            var found = false
            for (label in 1 until labelCount) { // 0 = background
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
                val x = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val touchesBorder = x <= 1 || y <= 1 || x + bw >= w - 1 || y + bh >= h - 1
                if (area >= minArea && touchesBorder) {
                    Core.compare(labels, Scalar(label.toDouble()), tmp, Core.CMP_EQ)
                    Core.bitwise_or(fingerMask, tmp, fingerMask)
                    found = true
                }
            }
            if (!found) return null

            // Grow the mask a little so finger edges / soft shadow are covered too.
            val dk = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
            Imgproc.dilate(fingerMask, fingerMask, dk)
            dk.release()

            val inpainted = Mat()
            Photo.inpaint(rgb, fingerMask, inpainted, 8.0, Photo.INPAINT_TELEA)
            val outRgba = Mat()
            Imgproc.cvtColor(inpainted, outRgba, Imgproc.COLOR_RGB2RGBA)
            inpainted.release()

            val out = Bitmap.createBitmap(outRgba.width(), outRgba.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)
            outRgba.release()
            return out
        } catch (e: Exception) {
            Log.e("FingerRemover", "Finger removal failed", e)
            return null
        } finally {
            rgba.release(); rgb.release(); ycrcb.release(); skin.release()
            labels.release(); stats.release(); centroids.release()
            fingerMask.release(); tmp.release()
        }
    }
}
