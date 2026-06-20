package com.jeiel85.clearpdflocal.domain.imaging

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Neural curved-page dewarping (the "왜곡 보정" feature): flattens a photographed curved book /
 * folded document into a fronto-parallel page using the UVDoc model run via ONNX Runtime.
 *
 * Fully on-device: the .onnx model is bundled in assets and ONNX Runtime executes it (incl. its
 * internal grid_sample) with zero network — brand-safe. The model takes an RGB image and returns
 * the rectified image directly.
 */
object DewarpEngine {

    /** Longest edge the model runs at — caps memory/latency; UVDoc is fully convolutional. */
    private const val MAX_DIM = 1280

    @Volatile
    private var session: OrtSession? = null

    private fun getSession(context: Context): OrtSession {
        session?.let { return it }
        synchronized(this) {
            session?.let { return it }
            val env = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("models/UVDoc_infer.onnx").use { it.readBytes() }
            val created = env.createSession(modelBytes, OrtSession.SessionOptions())
            session = created
            return created
        }
    }

    /** Returns the flattened bitmap, or null on any failure (caller keeps the original). */
    fun dewarp(context: Context, src: Bitmap): Bitmap? {
        return try {
            val env = OrtEnvironment.getEnvironment()
            val ortSession = getSession(context)

            val input = scaleDown(src, MAX_DIM)
            val w = input.width
            val h = input.height
            val chw = toChwRgbNormalized(input)
            if (input !== src) input.recycle()

            OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, h.toLong(), w.toLong()))
                .use { tensor ->
                    ortSession.run(mapOf("image" to tensor)).use { result ->
                        val out = result[0] as OnnxTensor
                        val shape = out.info.shape // [1, 3, H, W]
                        val oh = shape[2].toInt()
                        val ow = shape[3].toInt()
                        chwToBitmap(out.floatBuffer, ow, oh)
                    }
                }
        } catch (t: Throwable) {
            Log.e("DewarpEngine", "dewarp failed", t)
            null
        }
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val s = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
    }

    /** Bitmap -> CHW, RGB, normalized to [0,1] (the model's expected input). */
    private fun toChwRgbNormalized(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            data[i] = ((p ushr 16) and 0xFF) / 255f          // R
            data[plane + i] = ((p ushr 8) and 0xFF) / 255f    // G
            data[2 * plane + i] = (p and 0xFF) / 255f          // B
        }
        return data
    }

    /** Model output (CHW, RGB, [0,1]) -> ARGB bitmap. */
    private fun chwToBitmap(buf: FloatBuffer, w: Int, h: Int): Bitmap {
        val plane = w * h
        val pixels = IntArray(plane)
        for (i in 0 until plane) {
            val r = (buf.get(i).coerceIn(0f, 1f) * 255f).toInt()
            val g = (buf.get(plane + i).coerceIn(0f, 1f) * 255f).toInt()
            val b = (buf.get(2 * plane + i).coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
