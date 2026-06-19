package com.jeiel85.clearpdflocal.domain.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Finds the four corners of the dominant document-shaped quadrilateral using a classic OpenCV
 * contour pipeline (blur -> Canny -> dilate -> largest convex 4-gon). Two entry points share
 * the same core:
 *  - [detectCorners] for a still capture (downscales a Bitmap first),
 *  - [detectCornersFromGray] for the live preview (operates on a grayscale frame directly).
 *
 * Returned corners are ordered top-left, top-right, bottom-right, bottom-left, in the
 * coordinate space of the input (full-res bitmap pixels / the given gray Mat). Null when no
 * confident quad is found.
 */
object DocumentDetector {

    /** Width a still bitmap is scaled to before contour search — enough detail, cheap. */
    private const val PROC_WIDTH = 500.0

    /** A quad must cover at least this fraction of the frame to count as the document. */
    private const val MIN_AREA_RATIO = 0.20

    fun detectCorners(bitmap: Bitmap): List<Point>? {
        if (!OpenCvInitializer.ensureInitialized()) return null
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val scale = PROC_WIDTH / src.width().toDouble()
        val small = Mat()
        val gray = Mat()
        try {
            Imgproc.resize(src, small, Size(PROC_WIDTH, src.height() * scale))
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            val quad = findQuad(gray) ?: return null
            return quad.map { Point(it.x / scale, it.y / scale) }
        } finally {
            src.release(); small.release(); gray.release()
        }
    }

    /**
     * Detects the document quad on an already-grayscale Mat (e.g. a camera frame's Y plane).
     * Returns corners in that Mat's pixel coordinates. The input Mat is not modified.
     */
    fun detectCornersFromGray(gray: Mat): List<Point>? {
        if (!OpenCvInitializer.ensureInitialized()) return null
        return findQuad(gray)
    }

    private fun findQuad(gray: Mat): List<Point>? {
        val work = Mat()
        val edges = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.GaussianBlur(gray, work, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(work, edges, 60.0, 180.0)
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val minArea = gray.width() * gray.height() * MIN_AREA_RATIO
            var best: List<Point>? = null
            var bestArea = 0.0
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area <= bestArea) continue
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                val peri = Imgproc.arcLength(curve, true)
                Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
                val pts = approx.toArray()
                curve.release(); approx.release()
                if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                    bestArea = area
                    best = pts.toList()
                }
            }
            return best?.let { order(it) }
        } finally {
            work.release(); edges.release(); kernel.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Orders four points as top-left, top-right, bottom-right, bottom-left. */
    private fun order(pts: List<Point>): List<Point> {
        val tl = pts.minByOrNull { it.x + it.y }!!
        val br = pts.maxByOrNull { it.x + it.y }!!
        val tr = pts.minByOrNull { it.y - it.x }!!
        val bl = pts.maxByOrNull { it.y - it.x }!!
        return listOf(tl, tr, br, bl)
    }
}
