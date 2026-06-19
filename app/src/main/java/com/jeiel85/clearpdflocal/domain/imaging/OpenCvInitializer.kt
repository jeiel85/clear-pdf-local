package com.jeiel85.clearpdflocal.domain.imaging

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Loads the bundled OpenCV native library exactly once. OpenCV 4.x ships the static
 * [OpenCVLoader.initLocal] entry point, so no Play-Services / network download is involved —
 * the .so is packaged in the APK, which keeps the no-INTERNET brand intact.
 */
object OpenCvInitializer {

    @Volatile
    private var initialized = false

    /** Returns true once OpenCV is ready; safe to call from any thread, on every frame. */
    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (!initialized) {
                initialized = OpenCVLoader.initLocal()
                if (!initialized) {
                    Log.e("OpenCvInitializer", "OpenCVLoader.initLocal() returned false")
                }
            }
        }
        return initialized
    }
}
