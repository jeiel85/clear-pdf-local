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
 * Best-effort glare reduction: removes small specular highlights (light reflections on glossy
 * paper) by inpainting them. Uses a top-hat transform so it targets spots that are *brighter
 * than their local background* — this distinguishes a glare spot from plain white paper.
 *
 * Honest limits: only small/medium reflections are handled; a large blown-out highlight has lost
 * its pixel information and cannot be recovered. Returns null when no glare spot is found.
 */
object GlareReducer {

    /** Only inpaint spots smaller than this fraction of the image (small reflections). */
    private const val MAX_SPOT_AREA_RATIO = 0.06

    /** How much brighter than the local background a pixel must be to count as specular. */
    private const val TOPHAT_THRESHOLD = 40.0

    fun reduceGlare(src: Bitmap): Bitmap? {
        if (!OpenCvInitializer.ensureInitialized()) return null

        val rgba = Mat()
        val rgb = Mat()
        val gray = Mat()
        val tophat = Mat()
        val mask = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val spotMask = Mat()
        val tmp = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

            // Top-hat highlights bright structures smaller than the kernel, above local background.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(31.0, 31.0))
            Imgproc.morphologyEx(gray, tophat, Imgproc.MORPH_TOPHAT, k)
            k.release()
            Imgproc.threshold(tophat, mask, TOPHAT_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            val closeK = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeK)
            closeK.release()

            val w = mask.width()
            val h = mask.height()
            val maxArea = w * h * MAX_SPOT_AREA_RATIO
            val labelCount = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)

            spotMask.create(h, w, CvType.CV_8UC1)
            spotMask.setTo(Scalar(0.0))
            var found = false
            for (label in 1 until labelCount) { // 0 = background
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
                if (area in 1.0..maxArea) { // small/medium reflections only — large glare is unrecoverable
                    Core.compare(labels, Scalar(label.toDouble()), tmp, Core.CMP_EQ)
                    Core.bitwise_or(spotMask, tmp, spotMask)
                    found = true
                }
            }
            if (!found) return null

            val dk = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
            Imgproc.dilate(spotMask, spotMask, dk)
            dk.release()

            val inpainted = Mat()
            Photo.inpaint(rgb, spotMask, inpainted, 6.0, Photo.INPAINT_TELEA)
            val outRgba = Mat()
            Imgproc.cvtColor(inpainted, outRgba, Imgproc.COLOR_RGB2RGBA)
            inpainted.release()

            val out = Bitmap.createBitmap(outRgba.width(), outRgba.height(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, out)
            outRgba.release()
            return out
        } catch (e: Exception) {
            Log.e("GlareReducer", "Glare reduction failed", e)
            return null
        } finally {
            rgba.release(); rgb.release(); gray.release(); tophat.release(); mask.release()
            labels.release(); stats.release(); centroids.release(); spotMask.release(); tmp.release()
        }
    }
}
