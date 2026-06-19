package com.jeiel85.clearpdflocal.domain.imaging

import org.opencv.core.Point

/**
 * One captured page in a scan session.
 *
 * The [rawPath] photo is kept so a different [mode] can be re-applied without re-shooting;
 * [processedPath] is the perspective-corrected + enhanced image that is shown in the UI and
 * compiled into the PDF. [corners] caches the detected document quad (raw pixel coords) so
 * mode switches skip re-detection.
 */
data class ScannedPage(
    val rawPath: String,
    val processedPath: String,
    val mode: ScanMode = ScanMode.AUTO,
    val corners: List<Point>? = null
)
