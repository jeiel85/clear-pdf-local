package com.jeiel85.clearpdflocal.domain.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a raw camera photo into a clean "scanned" page: perspective-corrects the detected
 * document quad to a fronto-parallel rectangle, then applies the chosen [ScanMode]
 * enhancement. All processing is on-device OpenCV — no network, no Play Services.
 */
object DocumentScanProcessor {

    /**
     * @param src     the raw captured bitmap (ARGB_8888).
     * @param corners four ordered corners (TL, TR, BR, BL) in [src] pixel coordinates, or null
     *                to keep the whole frame (no perspective correction).
     * @param mode    enhancement style.
     */
    fun process(src: Bitmap, corners: List<Point>?, mode: ScanMode): Bitmap {
        OpenCvInitializer.ensureInitialized()

        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)

        val warped = if (corners != null && corners.size == 4) warp(rgba, corners) else rgba.clone()
        rgba.release()

        val enhanced = enhance(warped, mode)
        warped.release()

        val out = Bitmap.createBitmap(enhanced.width(), enhanced.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhanced, out)
        enhanced.release()
        return out
    }

    /** Perspective-warps the quad to a rectangle sized by its own edge lengths. */
    private fun warp(rgba: Mat, c: List<Point>): Mat {
        val (tl, tr, br, bl) = c
        val width = max(dist(br, bl), dist(tr, tl)).coerceAtLeast(1.0)
        val height = max(dist(tr, br), dist(tl, bl)).coerceAtLeast(1.0)

        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(width - 1, 0.0),
            Point(width - 1, height - 1),
            Point(0.0, height - 1)
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(rgba, out, transform, Size(width, height))
        srcPts.release(); dstPts.release(); transform.release()
        return out
    }

    /** Applies the enhancement for [mode]; always returns an RGBA Mat ready for matToBitmap. */
    private fun enhance(rgba: Mat, mode: ScanMode): Mat = when (mode) {
        ScanMode.COLOR -> {
            val out = Mat()
            rgba.convertTo(out, -1, 1.15, 8.0) // mild contrast + brightness
            out
        }

        ScanMode.GRAYSCALE -> {
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, gray)
            val out = Mat()
            Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
            gray.release()
            out
        }

        ScanMode.BLACK_WHITE -> {
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val bg = background(gray)
            val norm = Mat()
            Core.divide(gray, bg, norm, 255.0) // flatten illumination
            gray.release(); bg.release()
            val bin = Mat()
            Imgproc.adaptiveThreshold(
                norm, bin, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 21, 10.0
            )
            norm.release()
            val out = Mat()
            Imgproc.cvtColor(bin, out, Imgproc.COLOR_GRAY2RGBA)
            bin.release()
            out
        }

        ScanMode.AUTO -> {
            // "Magic colour": divide each colour channel by the local background luminance so
            // shadows / uneven lighting flatten out to clean white while ink stays dark.
            val rgb = Mat()
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val gray = Mat()
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            val bg = background(gray)
            gray.release()

            val channels = ArrayList<Mat>()
            Core.split(rgb, channels)
            rgb.release()
            for (ch in channels) Core.divide(ch, bg, ch, 255.0)
            bg.release()

            val merged = Mat()
            Core.merge(channels, merged)
            channels.forEach { it.release() }
            merged.convertTo(merged, -1, 1.1, 0.0) // gentle contrast

            val out = Mat()
            Imgproc.cvtColor(merged, out, Imgproc.COLOR_RGB2RGBA)
            merged.release()
            out
        }
    }

    /** Estimates the page background (illumination) via morphological close + blur. */
    private fun background(gray: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        val bg = Mat()
        Imgproc.morphologyEx(gray, bg, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(bg, bg, Size(21.0, 21.0), 0.0)
        kernel.release()
        return bg
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
}
