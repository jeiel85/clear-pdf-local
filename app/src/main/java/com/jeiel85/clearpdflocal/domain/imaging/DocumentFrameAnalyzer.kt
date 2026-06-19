package com.jeiel85.clearpdflocal.domain.imaging

import android.graphics.PointF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Per-frame document detector for the live camera preview, powering auto-capture.
 *
 * Each analysis frame's Y (luma) plane is read as a grayscale image, rotated upright, and run
 * through [DocumentDetector]. The detected quad is reported as normalized corners for the
 * on-screen overlay. The analyzer also tracks stability: once a page is held still for
 * [STABLE_FRAMES] consecutive frames it raises [DetectionResult.autoCapture] once, then
 * disarms until the page leaves the frame — so flipping to the next book page re-arms it.
 */
class DocumentFrameAnalyzer(
    private val onResult: (DetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * @param corners normalized (0..1) upright corners TL,TR,BR,BL, or null when no quad.
     * @param srcWidth/srcHeight upright frame dimensions, for mapping the overlay with the
     *        same FILL_CENTER scaling the preview uses.
     * @param autoCapture true on the single frame the page becomes stable & armed.
     */
    data class DetectionResult(
        val corners: List<PointF>?,
        val srcWidth: Int,
        val srcHeight: Int,
        val autoCapture: Boolean
    )

    private var lastCorners: List<PointF>? = null
    private var stableFrames = 0
    private var armed = true

    override fun analyze(image: ImageProxy) {
        var gray: Mat? = null
        try {
            if (!OpenCvInitializer.ensureInitialized()) {
                onResult(DetectionResult(null, 0, 0, false))
                return
            }
            gray = imageToGrayUpright(image)
            val w = gray.width()
            val h = gray.height()
            val cornersPx = DocumentDetector.detectCornersFromGray(gray)
            val norm = cornersPx?.map {
                PointF((it.x / w).toFloat(), (it.y / h).toFloat())
            }
            val auto = updateStability(norm)
            onResult(DetectionResult(norm, w, h, auto))
        } catch (t: Throwable) {
            onResult(DetectionResult(null, 0, 0, false))
        } finally {
            gray?.release()
            image.close()
        }
    }

    /** Builds an upright grayscale Mat from the frame's Y plane, applying rotationDegrees. */
    private fun imageToGrayUpright(image: ImageProxy): Mat {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val buffer: ByteBuffer = plane.buffer

        val sensor = Mat(h, w, CvType.CV_8UC1)
        if (rowStride == w) {
            val data = ByteArray(w * h)
            buffer.get(data)
            sensor.put(0, 0, data)
        } else {
            val row = ByteArray(w)
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, w)
                sensor.put(y, 0, row)
            }
        }

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return sensor
        val upright = Mat()
        when (rotation) {
            90 -> Core.rotate(sensor, upright, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(sensor, upright, Core.ROTATE_180)
            270 -> Core.rotate(sensor, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> sensor.copyTo(upright)
        }
        sensor.release()
        return upright
    }

    /** Updates stability state; returns true on the frame an auto-capture should fire. */
    private fun updateStability(corners: List<PointF>?): Boolean {
        if (corners == null || corners.size != 4) {
            lastCorners = null
            stableFrames = 0
            armed = true // page left the frame -> re-arm for the next one
            return false
        }
        val prev = lastCorners
        lastCorners = corners
        if (prev == null || prev.size != corners.size) {
            stableFrames = 0
            return false
        }
        var maxMove = 0f
        for (i in corners.indices) {
            maxMove = maxOf(maxMove, abs(corners[i].x - prev[i].x), abs(corners[i].y - prev[i].y))
        }
        stableFrames = if (maxMove < STABLE_MOVE) stableFrames + 1 else 0

        if (armed && stableFrames >= STABLE_FRAMES) {
            armed = false
            stableFrames = 0
            return true
        }
        return false
    }

    companion object {
        /** Max normalized corner movement between frames to count as "held still". */
        private const val STABLE_MOVE = 0.025f

        /** Consecutive still frames required before auto-capture fires. */
        private const val STABLE_FRAMES = 8
    }
}
