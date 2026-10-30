package io.github.jackwener.dsh.core.capability

import kotlinx.coroutines.delay
import kotlin.math.abs

/** Detached observations let compound tools use the actual result, never parse model-facing prose. */
data class ScreenObservation(
    val frame: Frame,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val text: List<OcrLine> = emptyList(),
    val stopReason: String? = null,
    val textIds: Map<String, OcrLine> = emptyMap(),
)

/** A selector is resolved anew before each action; ambiguous matches never pick the first row. */
data class PhoneSelector(val text: String? = null, val resourceId: String? = null, val role: String? = null, val contains: Boolean = false) {
    init { if (text.isNullOrBlank() && resourceId.isNullOrBlank() && role.isNullOrBlank()) throw InvalidArgsException("target needs text, resourceId or role") }

    fun resolve(frame: Frame): String {
        val matches = frame.elements.indices.filter { i ->
            val e = frame.elements[i]
            !e.system && (resourceId == null || e.resId == resourceId.substringAfter(":id/")) &&
                (role == null || e.cls.equals(role.substringAfterLast('.'), true)) &&
                (text == null || listOfNotNull(e.text, e.desc).any { if (contains) it.contains(text, true) else it == text })
        }
        if (matches.isEmpty()) throw InvalidArgsException("Target not found; read the current screen and revise the remaining steps.")
        if (matches.size != 1) throw InvalidArgsException("Target matches ${matches.size} elements; add resourceId, role or exact text.")
        val i = matches.single()
        if ("disabled" in frame.elements[i].flags) throw InvalidArgsException("Target is disabled.")
        return frame.ids[i]
    }
}

data class CollectionRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { if (left < 0 || top < 0 || right <= left || bottom <= top) throw InvalidArgsException("region needs 0 <= left < right and 0 <= top < bottom") }
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/** Merge only adjacent page overlap. Repeated values elsewhere (e.g. chat messages) are retained. */
class OrderedPages(private val backwards: Boolean, private val maxItems: Int, private val maxChars: Int) {
    val items = mutableListOf<String>()
    var truncated = false
        private set
    private var chars = 0

    fun add(page: List<String>): Int {
        val a = if (backwards) page else items
        val b = if (backwards) items else page
        val overlap = (minOf(a.size, b.size) downTo 1).firstOrNull { n ->
            (0 until n).all { a[a.size - n + it] == b[it] }
        } ?: 0
        val fresh = if (backwards) page.dropLast(overlap) else page.drop(overlap)
        val accepted = mutableListOf<String>()
        // When collecting backwards, keep the suffix adjacent to the pages already read.
        for (line in if (backwards) fresh.asReversed() else fresh) {
            if (items.size + accepted.size >= maxItems || chars + line.length > maxChars) { truncated = true; break }
            accepted += line
            chars += line.length
        }
        if (backwards) items.addAll(0, accepted.asReversed()) else items.addAll(accepted)
        return accepted.size
    }
}

/** Small visual signature. Dimensions are part of identity; small colour noise is tolerated. */
class VisualStamp(val width: Int, val height: Int, private val pixels: IntArray) {
    fun same(other: VisualStamp, tolerance: Double = 0.02): Boolean {
        if (width != other.width || height != other.height || pixels.size != other.pixels.size || pixels.isEmpty()) return false
        val allowed = (pixels.size * tolerance).toInt()
        var changed = 0
        for (i in pixels.indices) {
            val p = pixels[i]; val q = other.pixels[i]
            val delta = abs((p shr 16 and 255) - (q shr 16 and 255)) +
                abs((p shr 8 and 255) - (q shr 8 and 255)) + abs((p and 255) - (q and 255))
            if (delta > 60 && ++changed > allowed) return false
        }
        return true
    }
}

/** Clock-injected adaptive wait, shared by post-action reads and OCR preflight. */
object ScreenSettling {
    data class Sample(val tree: Int, val eventAt: Long)
    data class Result(val quiet: Boolean, val changed: Boolean, val elapsedMs: Long)

    suspend fun await(
        baseline: Int,
        requireChange: Boolean,
        now: () -> Long,
        sample: suspend () -> Sample,
        sleep: suspend (Long) -> Unit = { delay(it) },
        quietMs: Long = 240,
        limitMs: Long = 1_800,
        pollMs: Long = 80,
    ): Result {
        val start = now()
        var previous = baseline
        var lastChange = start
        var changed = false
        while (true) {
            val s = sample()
            val at = now()
            if (s.tree != previous) { lastChange = at; previous = s.tree }
            // A transient animation can return to the original layout before real data arrives.
            changed = s.tree != baseline
            val quiet = at - maxOf(lastChange, s.eventAt) >= quietMs
            if (quiet && (changed || !requireChange) || at - start >= limitMs)
                return Result(quiet, changed, at - start)
            sleep(minOf(pollMs, (limitMs - (at - start)).coerceAtLeast(1)))
        }
    }
}
