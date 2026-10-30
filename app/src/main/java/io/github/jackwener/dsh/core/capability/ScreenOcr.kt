package io.github.jackwener.dsh.core.capability

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognized line with its centre in screen pixels (tappable as `t<n>`). */
data class OcrLine(val text: String, val x: Int, val y: Int,
    val left: Int = x - 24, val top: Int = y - 12, val right: Int = x + 24, val bottom: Int = y + 12)
data class RecognizedText(val lines: List<OcrLine>, val targets: List<VisualStamp>)

/**
 * On-device text recognition (ML Kit, bundled Chinese model, which also reads Latin script) for
 * screens whose accessibility tree has little text: custom-drawn UIs, Flutter, games, opaque WebViews.
 */
@Singleton
class ScreenOcr @Inject constructor() {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    /** Takes ownership of [bitmap]; release it when ML Kit finishes, even if the caller was cancelled. */
    suspend fun read(bitmap: Bitmap): RecognizedText {
        return suspendCancellableCoroutine { cont ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                    try {
                        if (!cont.isActive) return@addOnCompleteListener
                        if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Text recognition failed")
                        val result = task.result
                        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                            val box = l.boundingBox ?: return@mapNotNull null
                            l.text.trim().takeIf { it.isNotEmpty() }?.let { OcrLine(it, box.centerX(), box.centerY(), box.left, box.top, box.right, box.bottom) }
                        }.sortedWith(compareBy({ it.y / ROW_PX }, { it.x }))
                        cont.resume(RecognizedText(lines, lines.map(bitmap::visualStamp)))
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally { bitmap.recycle() }
                }
            } catch (e: Exception) {
                bitmap.recycle()
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    companion object {
        /** Lines whose centres are this close vertically count as one row when sorting. */
        private const val ROW_PX = 24

        /** Fewer readable elements than this: the tree is not enough, recognize the screenshot too. */
        const val SPARSE = 4

        fun render(lines: List<OcrLine>): String = lines.withIndex().joinToString("") { (i, l) -> "[t${i + 1}] \"${l.text}\" @${l.x},${l.y}\n" }
    }
}
