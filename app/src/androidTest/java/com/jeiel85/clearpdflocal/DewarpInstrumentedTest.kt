package com.jeiel85.clearpdflocal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jeiel85.clearpdflocal.domain.imaging.DewarpEngine
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * On-device feasibility check for Phase 3: load the bundled UVDoc model via ONNX Runtime,
 * dewarp a known curved-document image, log the latency, and save the result so it can be
 * pulled and eyeballed.
 */
@RunWith(AndroidJUnit4::class)
class DewarpInstrumentedTest {

    @Test
    fun dewarpRunsOnDevice() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context

        val input = testContext.assets.open("doc_test.jpg").use { BitmapFactory.decodeStream(it) }
        assertNotNull("test image decoded", input)

        // Warm-up (model load + first inference) then a timed run.
        val warm = DewarpEngine.dewarp(appContext, input!!)
        assertNotNull("dewarp output (warm-up)", warm)

        val t0 = SystemClock.elapsedRealtime()
        val out = DewarpEngine.dewarp(appContext, input)
        val ms = SystemClock.elapsedRealtime() - t0

        Log.i(
            "DewarpTest",
            "latency=${ms}ms  input=${input.width}x${input.height}  output=${out?.width}x${out?.height}"
        )
        assertNotNull("dewarp output", out)

        val outFile = File(appContext.getExternalFilesDir(null), "dewarp_out.png")
        FileOutputStream(outFile).use { out!!.compress(Bitmap.CompressFormat.PNG, 95, it) }
        Log.i("DewarpTest", "saved ${outFile.absolutePath}")
    }
}
