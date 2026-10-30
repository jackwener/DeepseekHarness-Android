package io.github.jackwener.dsh.core.capability

import android.graphics.Bitmap

/** Sample the app area (exclude the clock and navigation bar) or one OCR target's padded bounds. */
fun Bitmap.visualStamp(line: OcrLine? = null): VisualStamp {
    val left = (line?.left?.minus(12) ?: 0).coerceIn(0, width - 1)
    val top = (line?.top?.minus(12) ?: (height * .06).toInt()).coerceIn(0, height - 1)
    val right = (line?.right?.plus(12) ?: width).coerceIn(left + 1, width)
    val bottom = (line?.bottom?.plus(12) ?: (height * .97).toInt()).coerceIn(top + 1, height)
    val cols = 48; val rows = if (line == null) 80 else 24
    return VisualStamp(width, height, IntArray(cols * rows) { i ->
        getPixel(left + (right - left - 1) * (i % cols) / (cols - 1), top + (bottom - top - 1) * (i / cols) / (rows - 1))
    })
}
