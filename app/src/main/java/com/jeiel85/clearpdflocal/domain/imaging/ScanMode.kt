package com.jeiel85.clearpdflocal.domain.imaging

/**
 * Document enhancement style applied after perspective correction.
 *
 * - [AUTO]: "magic colour" — flattens uneven lighting / shadows while keeping the page colour.
 *   The default, closest to what users expect from a premium scanner.
 * - [COLOR]: true-to-photo, only a mild contrast lift. Best for photos / coloured graphics.
 * - [GRAYSCALE]: grayscale with local-contrast (CLAHE) boost.
 * - [BLACK_WHITE]: crisp bi-level scan via adaptive thresholding. Best for plain text.
 */
enum class ScanMode {
    AUTO,
    COLOR,
    GRAYSCALE,
    BLACK_WHITE
}
