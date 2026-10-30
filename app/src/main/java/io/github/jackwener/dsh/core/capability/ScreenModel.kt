package io.github.jackwener.dsh.core.capability

import kotlin.math.abs
import kotlin.math.hypot

/** One element of a screen read, detached from AccessibilityNodeInfo so ids, diffs and effects stay pure. */
data class UiElement(
    val cls: String,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val x: Int,
    val y: Int,
    val depth: Int = 0,
    val flags: List<String> = emptyList(),
    val editable: Boolean = false,
    /** Status bar / notification shade (listed under its own header, ignored by diffs and fingerprints). */
    val system: Boolean = false,
    val left: Int = x, val top: Int = y, val right: Int = x + 1, val bottom: Int = y + 1,
    /** Collection keeps long native labels; ordinary screen listings retain their compact preview. */
    val fullText: String? = null, val textClipped: Boolean = false,
) {
    /** What makes two reads' elements "the same": an edit field keeps its identity while its text changes. */
    val identity: String get() = if (editable) "$cls\u0000\u0000$desc\u0000$resId" else "$cls\u0000$text\u0000$desc\u0000$resId"
    val label: String? get() = text?.takeIf { it.isNotBlank() } ?: desc?.takeIf { it.isNotBlank() }
    val readable: Boolean get() = !text.isNullOrBlank() || !desc.isNullOrBlank()

    /** `[e3] Button "OK" desc="…" #res {flags} @x,y`, without indentation. */
    fun line(id: String): String = buildString {
        append('[').append(id).append("] ").append(cls)
        if (!text.isNullOrBlank()) append(" \"").append(text.replace('\n', ' ')).append('"')
        if (!desc.isNullOrBlank() && desc != text) append(" desc=\"").append(desc.replace('\n', ' ')).append('"')
        resId?.let { append(" #").append(it) }
        if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
        append(" @").append(x).append(',').append(y)
    }

    /** The line without the position: an element that only drifted a few pixels is not "changed". */
    fun content(): String = line("").substringBeforeLast(" @")
}

/** A screen read with stable ids ([ids] parallel to [elements]). */
class Frame(val seq: Int, val packageName: String?, val elements: List<UiElement>, val ids: List<String>) {
    private val index by lazy { ids.withIndex().associate { it.value to it.index } }
    fun indexOf(id: String): Int? = index[id]

    /** Hash of what the app shows (texts and flags, system UI excluded): equal before and after = nothing happened. */
    val fingerprint: Int by lazy { elements.filterNot { it.system }.map { it.content() to it.fullText }.hashCode() * 31 + (packageName?.hashCode() ?: 0) }

    /** Settling and coordinate validation must notice motion even when the labels did not change. */
    val layoutFingerprint: Int by lazy {
        elements.filterNot { it.system }.map { listOf(it.content(), it.fullText, it.x, it.y, it.left, it.top, it.right, it.bottom) }.hashCode() * 31 + (packageName?.hashCode() ?: 0)
    }

    /** Elements that carry readable text (few of them = custom-drawn UI, try text recognition). */
    val readableCount: Int get() = elements.count { !it.system && it.readable }

    private val systemCount: Int get() = elements.count { it.system }

    /** The system UI changed shape (the shade opened or closed), not just the clock or an icon. */
    fun systemUiChanged(other: Frame): Boolean = abs(systemCount - other.systemCount) > SHADE_DELTA

    fun render(): String = buildString {
        var systemHeader = false
        elements.forEachIndexed { i, e ->
            if (e.system && !systemHeader) { append("-- system UI (status bar / notification shade) --\n"); systemHeader = true }
            append("  ".repeat(minOf(e.depth, 12))).append(e.line(ids[i])).append('\n')
        }
    }

    private companion object {
        const val SHADE_DELTA = 3
    }
}

/**
 * Ids that survive re-reads: an element that is still there in the next read (same class, text,
 * description and resource id, centre moved at most [maxShift] px, same foreground app) keeps its
 * `e` number, so the ids the model holds are not all invalidated by one refresh. Numbers only go
 * up within one tracker; retired ids remember their element (in the same app) for [resolve].
 */
class StableIds(private val maxShift: Int = 48, private val maxRelocate: Int = 500, private val prefix: String = "e") {
    private var next = 1
    private var seq = 0
    var latest: Frame? = null
        private set
    private val retired = object : LinkedHashMap<String, UiElement>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UiElement>) = size > 600
    }

    /** An empty read during a transition (no app, no elements) does not become the base of the next one. */
    @Synchronized
    fun assign(packageName: String?, elements: List<UiElement>): Frame {
        if (packageName == null && elements.isEmpty()) return Frame(++seq, null, elements, emptyList())
        val prev = latest?.takeIf { it.packageName == packageName }
        val free = prev?.elements?.indices?.groupBy { prev.elements[it].identity }?.mapValues { it.value.toMutableList() }.orEmpty()
        val ids = elements.map { e ->
            val candidates = free[e.identity]
            val best = candidates?.minByOrNull { j -> dist(prev!!.elements[j], e) }?.takeIf { j -> dist(prev!!.elements[j], e) <= maxShift }
            if (best != null) { candidates.remove(best); prev!!.ids[best] } else "$prefix${next++}"
        }
        if (prev == null) retired.clear()
        prev?.let { old ->
            val kept = ids.toSet()
            old.ids.forEachIndexed { i, id -> if (id !in kept) retired[id] = old.elements[i] }
        }
        ids.forEach { retired.remove(it) }
        return Frame(++seq, packageName, elements, ids).also { latest = it }
    }

    sealed interface Lookup {
        data class Found(val index: Int) : Lookup
        /** [id] is gone from [frame], but an element with its features is there now (at [index]). */
        data class Relocated(val index: Int, val newId: String) : Lookup
        data object Gone : Lookup
        data object Unknown : Lookup
    }

    /**
     * Finds [id] in [frame] (normally [latest]); a retired id is looked up again by its features, but
     * only when exactly one element of the same app has them, within [maxRelocate] px: with two
     * "关注" buttons, or a button that moved across the page, the model has to read again.
     */
    @Synchronized
    fun resolve(id: String, frame: Frame? = latest): Lookup {
        frame ?: return Lookup.Unknown
        frame.indexOf(id)?.let { return Lookup.Found(it) }
        val was = retired[id] ?: return if (issued(id)) Lookup.Gone else Lookup.Unknown
        if (frame.packageName != latest?.packageName) return Lookup.Gone
        val i = frame.elements.indices.singleOrNull { frame.elements[it].identity == was.identity }
            ?.takeIf { dist(frame.elements[it], was) <= maxRelocate } ?: return Lookup.Gone
        return Lookup.Relocated(i, frame.ids[i])
    }

    private fun issued(id: String) = id.startsWith(prefix) && (id.removePrefix(prefix).toIntOrNull()?.let { it in 1 until next } == true)

    private fun dist(a: UiElement, b: UiElement) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    companion object {
        /** "e12", "12" or 12 → "e12"; OCR ids ("t3") stay as they are. */
        fun normalize(raw: String): String = raw.trim().removePrefix("[").removeSuffix("]").let { if (it.toIntOrNull() != null) "e$it" else it.lowercase() }
    }
}

/** What changed between the read the model saw and the read after an action. */
class ScreenDiff(val added: List<Int>, val removed: List<Int>, val changed: List<Int>, val pageChanged: Boolean) {
    val empty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /** `+ [e31] …` / `- [e12] …` / `~ [e7] …` lines ([removed] index into [old], the rest into [new]). */
    fun render(old: Frame, new: Frame): String = buildString {
        added.forEach { append("+ ").append(new.elements[it].line(new.ids[it])).append('\n') }
        changed.forEach { append("~ ").append(new.elements[it].line(new.ids[it])).append('\n') }
        removed.forEach { append("- ").append(old.elements[it].line(old.ids[it])).append('\n') }
    }

    companion object {
        /** More than this share of the elements came or went: a new page, sent in full. */
        const val PAGE_SHARE = 0.5

        fun of(old: Frame, new: Frame): ScreenDiff {
            val oldApp = old.elements.indices.filterNot { old.elements[it].system }
            val newApp = new.elements.indices.filterNot { new.elements[it].system }
            val oldIds = oldApp.associateBy { old.ids[it] }
            val newIds = newApp.map { new.ids[it] }.toSet()
            val added = newApp.filter { new.ids[it] !in oldIds }
            val removed = oldApp.filter { old.ids[it] !in newIds }
            val changed = newApp.filter { i -> oldIds[new.ids[i]]?.let { old.elements[it].content() != new.elements[i].content() } == true }
            val total = maxOf(oldApp.size, newApp.size, 1)
            val page = old.packageName != new.packageName || (added.size + removed.size).toDouble() / (2 * total) > PAGE_SHARE ||
                (oldApp.isNotEmpty() && removed.size.toDouble() / oldApp.size > PAGE_SHARE)
            return ScreenDiff(added, removed, changed, page)
        }
    }
}

/**
 * "Too frequent / limit reached" messages that appeared after an action (toasts, dialogs). Only
 * short texts that were not on screen before count: help pages that mention limits do not.
 */
object LimitNotice {
    private val WORDS = listOf(
        // Chinese: "too often / too fast", "quota used up", "come back later", throttling.
        "过于频繁", "太频繁", "频繁了", "操作太快", "手速太快", "请求过多", "访问量过大", "限流",
        "次数已用完", "次数不足", "额度已用完", "今日已用完", "今日上限", "今天的次数",
        "稍后再试", "稍后重试", "请稍后再", "休息一下", "明天再来", "明日再试", "暂时无法操作", "暂时被限制",
        // English (compacted: no spaces, lower case).
        "toomanyrequests", "toomanyattempts", "toomany", "ratelimit", "slowdown", "limitreached", "reachedthelimit",
        "reachedyourlimit", "dailylimit", "limitexceeded", "tryagainlater", "tryagaintomorrow", "temporarilyblocked",
        "temporarilyrestricted", "doingthattoooften",
    )
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")
    private const val MAX_CHARS = 120

    fun find(before: Frame?, after: Frame): String? {
        val earlier = before?.elements.orEmpty().mapNotNull { it.label }.map(::compact).toSet()
        return after.elements.filter { !it.editable && !it.system }.mapNotNull { it.label }.firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}

/**
 * Taps that reach other people (send, post, follow, apply…): these are paced, see [OutwardPacing].
 * States and views are not (已关注, 取消关注, 查看3条回复), nor asking for a verification code.
 */
object OutwardAction {
    private val WORDS = listOf("发送", "提交", "发布", "发表", "评论", "打招呼", "投递", "关注", "回复", "立即沟通", "send", "submit", "post", "reply", "comment", "follow")
    private val NOT = Regex("^(已|取消|查看)|验证码")
    private const val MAX_CHARS = 10

    fun matches(label: String?): Boolean {
        val l = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS } ?: return false
        if (NOT.containsMatchIn(l)) return false
        return WORDS.any { w -> if (w[0].code < 0x80) l == w || l.startsWith("$w ") || l.endsWith(" $w") else w in l }
    }
}
