package io.github.jackwener.dsh.core.capability.tools

import android.os.SystemClock
import dagger.Lazy
import io.github.jackwener.dsh.core.capability.*
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, sequential native-phone operations. All child calls still enter CapabilityHub. */
@Singleton
class PhoneTools @Inject constructor(private val hub: Lazy<CapabilityHub>, private val agent: AgentScreen) : ToolSet {
    private val serial = Mutex()

    override val tools = listOf(
        object : Tool("phone_run", ToolGroup.Screen, Risk.write, true,
            "Run 1–30 known native-phone steps sequentially in one call. Each target is matched against a fresh screen; ambiguous targets stop. " +
                "Use only for a predictable path, with wait/assert at asynchronous boundaries. Stops on failure, no effect, app/display change or user takeover. " +
                "Returns completed steps and the final screen; never replay completed steps after an interruption. Children retain their own capability/mode/automation checks.", {
                raw("commands", buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", 30)
                    put("items", commandSchema())
                }, required = true)
                string("screen", "main = phone's visible screen; agent = this session's background display", enum = listOf("main", "agent"))
                string("package", "Expected initial app, optional. A launch step explicitly changes it.")
                bool("ocr", "Force validated screenshot text before each step for canvas controls; slower")
            }, timeoutMs = 180_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                validateScreen(args)
                val commands = validatePhoneCommands(args["commands"])
                return execute(call) { runCommands(args, call, commands) }
            }
        },
        object : Tool("phone_collect", ToolGroup.Screen, Risk.write, true,
            "Read and scroll a native app list across multiple screens in one call. Keeps order and repeated values, merging only adjacent page overlap. " +
                "Stops after two pages with no new text, a failed scroll, app/display change, an unreadable page or a limit. " +
                "Returns partial items, pages, scrolls, stopReason and truncated. Set a content region to exclude fixed headers/footers, " +
                "or target the scrollable container. This scrolls the phone; it is not a background network fetch.", {
                string("screen", "main or agent", enum = listOf("main", "agent"))
                string("package", "Expected app; default is the app on the first page")
                string("direction", "Default down; up returns items in reading order", enum = listOf("down", "up"))
                raw("target", selectorSchema())
                raw("region", SchemaBuilder().apply {
                    int("left", "Left edge in screen pixels", true); int("top", "Top edge", true)
                    int("right", "Right edge", true); int("bottom", "Bottom edge", true)
                }.build())
                int("maxPages", "1–30, default 10"); int("maxItems", "1–1000, default 300")
                int("maxChars", "1–60000, default 24000")
                bool("ocr", "Collect screenshot text instead of accessible labels; slower, frames are validated")
            }, timeoutMs = 180_000) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                validateScreen(args)
                return execute(call) { collect(args, call) }
            }
        },
    )

    private suspend fun execute(call: CallContext, block: suspend () -> ToolResult): ToolResult = serial.withLock {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: PlanStopped) { call.partialFailure(e.code, e.message ?: e.code) ?: ToolResult.error(e.code, e.message ?: e.code) }
        catch (e: InvalidArgsException) { call.partialFailure("invalid_args", e.message ?: "Invalid plan") ?: ToolResult.error("invalid_args", e.message ?: "Invalid plan") }
        catch (e: Exception) { call.partialFailure("failed", e.message ?: "Plan failed") ?: ToolResult.error("failed", e.message ?: "Plan failed") }
    }

    private class PlanStopped(val code: String, message: String) : Exception(message)

    private inner class Execution(val args: JsonObject, val call: CallContext) {
        var screen = args.str("screen")
        var display: Int? = null
        var expectedPackage = args.str("package")
        var last: ToolResult? = null
        val observation: ScreenObservation get() = last?.observation ?: throw PlanStopped("unreadable_screen", "No verified screen observation is available.")

        suspend fun child(name: String, values: JsonObject = JsonObject(emptyMap()), allowAppChange: Boolean = false): ToolResult {
            call.checkCurrent()
            if (display != null && agent.target(call.sessionId, screen).displayId != display)
                throw PlanStopped("display_changed", "The selected display changed. No further steps were run.")
            val result = hub.get().invokeNested(name, JsonObject(values + listOfNotNull(screen?.let { "screen" to JsonPrimitive(it) }).toMap()), call)
            last = result
            result.observation?.let { o -> call.progress?.let { call.report(it.withObservation(o)) } }
            if (!result.ok) throw PlanStopped(result.errorCode ?: "step_failed", result.errorMessage ?: "Step failed")
            result.observation?.let { o ->
                if (display == null) { display = o.displayId; screen = if (o.displayId == 0) "main" else "agent" }
                if (o.displayId != display) throw PlanStopped("display_changed", "The result came from another display.")
                o.stopReason?.let { throw PlanStopped(it, "The screen could not be used safely ($it). Inspect it before continuing.") }
                if (!allowAppChange && expectedPackage != null && expectedPackage != o.frame.packageName)
                    throw PlanStopped("app_changed", "Expected $expectedPackage, found ${o.frame.packageName}; remaining steps were not run.")
                if (expectedPackage == null) expectedPackage = o.frame.packageName
            }
            return result
        }

        suspend fun read() = child("screen_read", buildJsonObject { if (args.bool("ocr") == true) put("ocr", true) })

        fun target(value: JsonElement): String {
            val s = parsePhoneSelector(value)
            val o = observation
            // OCR text is allowed only when no native element matches; its id is revalidated by screen_tap.
            if (s.resourceId == null && s.role == null && o.frame.elements.none { e ->
                    !e.system && listOfNotNull(e.text, e.desc).any { if (s.contains) it.contains(s.text!!, true) else it == s.text }
                }) {
                val found = o.textIds.filterValues { if (s.contains) it.text.contains(s.text!!, true) else it.text == s.text }
                if (found.size == 1) return found.keys.single()
            }
            return s.resolve(o.frame)
        }
    }

    private suspend fun runCommands(args: JsonObject, call: CallContext, commands: List<JsonObject>): ToolResult {
        val e = Execution(args, call)
        val completed = mutableListOf<JsonObject>()
        val start = SystemClock.elapsedRealtime()
        fun report(inFlight: Int? = null): ToolResult = ToolResult.text(buildJsonObject {
            put("completed", JsonArray(completed)); put("total", commands.size)
            inFlight?.let { put("inFlight", it) }
            put("elapsedMs", SystemClock.elapsedRealtime() - start)
            put("resume", "Inspect the current screen and plan only remaining steps; do not replay completed or uncertain actions.")
        }.toString()).let { result -> e.last?.observation?.let(result::withObservation) ?: result }.also(call::report)

        report()
        for ((i, c) in commands.withIndex()) {
            report(i + 1)
            val op = c.reqStr("op")
            val before = SystemClock.elapsedRealtime()
            if (op != "launch") e.read()
            val result = when (op) {
                "observe", "assert" -> {
                    if (op == "assert") c["target"]?.let { e.target(it) }
                    c.str("package")?.let { if (e.observation.frame.packageName != it) throw PlanStopped("assertion_failed", "Expected app $it") }
                    e.last!!
                }
                "launch" -> {
                    e.expectedPackage = c.reqStr("package")
                    e.child("app_launch", JsonObject(c.filterKeys { it in setOf("package", "activity") }), allowAppChange = true)
                    e.child("screen_wait", buildJsonObject { put("package", e.expectedPackage!!); put("timeoutMs", 10_000) })
                }
                else -> {
                    val tool = when (op) { "tap" -> "screen_tap"; "type" -> "screen_type"; "scroll" -> "screen_scroll"
                        "swipe" -> "screen_swipe"; "key" -> "screen_key"; else -> "screen_wait" }
                    val childArgs = c.filterKeys { it != "op" && it != "target" }.toMutableMap()
                    c["target"]?.let { childArgs["node"] = JsonPrimitive(e.target(it)) }
                    if (op == "swipe") {
                        val o = e.observation
                        for (k in listOf("x1", "x2")) if (c.int(k)!! >= o.width) throw InvalidArgsException("$k is outside the selected display")
                        for (k in listOf("y1", "y2")) if (c.int(k)!! >= o.height) throw InvalidArgsException("$k is outside the selected display")
                    }
                    e.child(tool, JsonObject(childArgs))
                }
            }
            completed += buildJsonObject { put("step", i + 1); put("op", op); put("elapsedMs", SystemClock.elapsedRealtime() - before) }
            report()
            // An explicit condition check is safe after an unobserved effect: it performs no
            // write and must succeed before another action. Never replay the preceding action.
            if (result.outcome == ActionOutcome.NoEffect && commands.getOrNull(i + 1)?.str("op") !in listOf("wait", "assert"))
                throw PlanStopped("no_effect", "Step ${i + 1} had no observed effect; remaining steps were not run.")
        }
        val summary = report()
        val finalScreen = e.last?.content?.filterIsInstance<Content.Text>()?.joinToString("\n") { it.text }
        return if (finalScreen == null) summary else ToolResult(true,
            summary.content + Content.Text("Final screen:\n$finalScreen"), observation = summary.observation)
    }

    private suspend fun collect(args: JsonObject, call: CallContext): ToolResult {
        val direction = args.str("direction") ?: "down"
        if (direction !in setOf("down", "up")) throw InvalidArgsException("direction must be down or up")
        val maxPages = bounded(args, "maxPages", 10, 30)
        val pages = OrderedPages(direction == "up", bounded(args, "maxItems", 300, 1_000), bounded(args, "maxChars", 24_000, 60_000))
        args["target"]?.let(::parsePhoneSelector)
        var region = args["region"]?.let { v ->
            val r = v as? JsonObject ?: throw InvalidArgsException("region must be an object")
            fun n(k: String) = (r[k] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: throw InvalidArgsException("region.$k must be an integer")
            CollectionRegion(n("left"), n("top"), n("right"), n("bottom"))
        }
        val e = Execution(args, call)
        val start = SystemClock.elapsedRealtime()
        var readPages = 0; var scrolls = 0; var noNew = 0
        var reason = "running"
        fun report(inFlight: String? = null) = ToolResult.text(buildJsonObject {
            put("items", JsonArray(pages.items.map(::JsonPrimitive))); put("pages", readPages); put("scrolls", scrolls)
            put("elapsedMs", SystemClock.elapsedRealtime() - start)
            put("stopReason", reason); put("truncated", pages.truncated || reason in setOf("max_pages", "unreadable_page", "source_text_limit"))
            inFlight?.let { put("inFlight", it) }
        }.toString()).let { result -> e.last?.observation?.let(result::withObservation) ?: result }.also(call::report)
        report("read")
        e.read()
        val first = e.observation
        val scrollables = first.frame.elements.filter { !it.system && "scroll" in it.flags }
        val container = args["target"]?.let { first.frame.indexOf(e.target(it))?.let(first.frame.elements::get) } ?: scrollables.singleOrNull()
        if (region == null && container != null && container.right > container.left && container.bottom > container.top)
            region = CollectionRegion(container.left.coerceAtLeast(0), container.top.coerceAtLeast(0), container.right.coerceAtMost(first.width), container.bottom.coerceAtMost(first.height))
        if (region != null && (region.right > first.width || region.bottom > first.height)) throw InvalidArgsException("region must fit the selected display")
        for (page in 1..maxPages) {
            val o = e.observation
            if (o.width != first.width || o.height != first.height) throw PlanStopped("display_changed", "Display dimensions changed while collecting.")
            val lines = if (args.bool("ocr") == true || o.frame.readableCount < ScreenOcr.SPARSE) o.text
                else o.frame.elements.filter { !it.system && it.readable }.map { OcrLine(it.fullText ?: it.label!!, it.x, it.y) }
            val text = lines.filter { region?.contains(it.x, it.y) != false }
                .distinctBy { Triple(it.text, it.x, it.y) }.sortedWith(compareBy({ it.y / 24 }, { it.x })).map { it.text.trim() }.filter { it.isNotEmpty() }
            if (text.isEmpty()) { reason = "unreadable_page"; break }
            noNew = if (pages.add(text) == 0) noNew + 1 else 0
            readPages++
            if (o.frame.elements.any { !it.system && it.textClipped && region?.contains(it.x, it.y) != false }) { reason = "source_text_limit"; break }
            if (pages.truncated) { reason = "output_limit"; break }
            if (noNew >= 2) { reason = "no_new_content"; break }
            if (page == maxPages) { reason = "max_pages"; break }
            report("scroll")
            val result = if (args["target"] != null) e.child("screen_scroll", buildJsonObject {
                put("direction", direction); put("node", e.target(args.getValue("target")))
            }) else if (region != null) {
                val r = region; val x = (r.left + r.right) / 2
                val hi = r.top + (r.bottom - r.top) / 5; val lo = r.bottom - (r.bottom - r.top) / 5
                e.child("screen_swipe", buildJsonObject {
                    put("x1", x); put("x2", x); put("y1", if (direction == "down") lo else hi)
                    put("y2", if (direction == "down") hi else lo); put("durationMs", 350)
                })
            } else e.child("screen_scroll", buildJsonObject { put("direction", direction) })
            scrolls++
            if (result.outcome == ActionOutcome.NoEffect) { reason = "scroll_no_effect"; break }
            // Force OCR on every page when requested; otherwise the action already returned a fresh observation.
            if (args.bool("ocr") == true) e.read()
        }
        return report()
    }

    private fun bounded(args: JsonObject, key: String, default: Int, max: Int): Int = (if (key !in args) default else
        (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw InvalidArgsException("$key must be an integer")).also {
        if (it !in 1..max) throw InvalidArgsException("$key must be between 1 and $max")
    }

    companion object {
        private fun validateScreen(args: JsonObject) {
            if (args.str("screen") !in listOf(null, "main", "agent")) throw InvalidArgsException("screen must be main or agent")
            if ("package" in args && ((args["package"] as? JsonPrimitive)?.isString != true || args.str("package").isNullOrBlank()))
                throw InvalidArgsException("package must be a nonempty string")
            if ("ocr" in args && ((args["ocr"] as? JsonPrimitive)?.isString != false || (args["ocr"] as JsonPrimitive).booleanOrNull == null))
                throw InvalidArgsException("ocr must be boolean")
        }
        private fun selectorSchema() = SchemaBuilder().apply {
            string("text", "Exact visible text/description (case sensitive by default)")
            string("resourceId", "Resource id, short or package:id/name")
            string("role", "Widget class, e.g. EditText, Button, ScrollView")
            bool("contains", "Substring text matching, case insensitive; still must be unique")
        }.build()

        private fun commandSchema() = SchemaBuilder().apply {
            string("op", "Operation", true, listOf("launch", "tap", "type", "scroll", "swipe", "key", "wait", "assert", "observe"))
            raw("target", selectorSchema())
            string("package", "launch/wait/assert: app package"); string("activity", "launch: exported activity")
            string("text", "type: content, or wait: visible text"); bool("append", "type: append"); bool("submit", "type: press Enter")
            string("kind", "tap: tap or long"); string("direction", "scroll: down/up/left/right")
            string("key", "key: enter/search/delete/back/home/recents")
            bool("gone", "wait: wait for text to disappear"); int("timeoutMs", "wait: 500–30000 ms")
            for (k in listOf("x1", "y1", "x2", "y2")) int(k, "swipe: screen pixels")
            int("durationMs", "swipe: 50–5000 ms")
        }.build()
    }
}

internal fun parsePhoneSelector(value: JsonElement): PhoneSelector {
    val s = value as? JsonObject ?: throw InvalidArgsException("target must be an object")
    if (s.keys.any { it !in setOf("text", "resourceId", "role", "contains") }) throw InvalidArgsException("Unknown target field")
    for (k in listOf("text", "resourceId", "role")) if (k in s && ((s[k] as? JsonPrimitive)?.isString != true || s.str(k).isNullOrBlank()))
        throw InvalidArgsException("target.$k must be a nonempty string")
    if ("contains" in s && (s["contains"] as? JsonPrimitive)?.booleanOrNull == null) throw InvalidArgsException("target.contains must be boolean")
    return PhoneSelector(s.str("text"), s.str("resourceId"), s.str("role"), s.bool("contains") == true)
}

/** Validate the entire plan before the first action, including later commands. */
internal fun validatePhoneCommands(value: JsonElement?): List<JsonObject> {
    val list = value as? JsonArray ?: throw InvalidArgsException("commands must be an array")
    if (list.size !in 1..30) throw InvalidArgsException("commands must contain 1–30 steps")
    val fields = mapOf(
        "launch" to setOf("package", "activity"), "tap" to setOf("target", "kind"),
        "type" to setOf("target", "text", "append", "submit"), "scroll" to setOf("target", "direction"),
        "swipe" to setOf("x1", "y1", "x2", "y2", "durationMs"), "key" to setOf("key"),
        "wait" to setOf("text", "package", "gone", "timeoutMs"), "assert" to setOf("target", "package"), "observe" to emptySet(),
    )
    return list.mapIndexed { i, v ->
        val c = v as? JsonObject ?: throw InvalidArgsException("Command ${i + 1} must be an object")
        val op = c.reqStr("op")
        val allowed = fields[op] ?: throw InvalidArgsException("Unknown operation $op")
        if (c.keys.any { it != "op" && it !in allowed }) throw InvalidArgsException("Unknown field in $op")
        for ((k, v) in c) when (k) {
            "target" -> Unit
            "append", "submit", "gone" -> if ((v as? JsonPrimitive)?.booleanOrNull == null) throw InvalidArgsException("$op.$k must be boolean")
            "x1", "y1", "x2", "y2", "durationMs", "timeoutMs" -> if ((v as? JsonPrimitive)?.intOrNull == null || (v as JsonPrimitive).isString) throw InvalidArgsException("$op.$k must be an integer")
            else -> if ((v as? JsonPrimitive)?.isString != true) throw InvalidArgsException("$op.$k must be a string")
        }
        c["target"]?.let(::parsePhoneSelector)
        when (op) {
            "launch" -> c.reqStr("package")
            "tap" -> { if (c["target"] == null) throw InvalidArgsException("tap requires a target"); if (c.str("kind") !in listOf(null, "tap", "long")) throw InvalidArgsException("Unknown tap kind") }
            "type" -> if (c.str("text") == null) throw InvalidArgsException("type requires text")
            "scroll" -> if (c.str("direction") !in listOf("down", "up", "left", "right")) throw InvalidArgsException("Unknown scroll direction")
            "key" -> if (c.str("key") !in listOf("enter", "search", "delete", "back", "home", "recents")) throw InvalidArgsException("Unknown key")
            "wait" -> { if (c.str("text") == null && c.str("package") == null) throw InvalidArgsException("wait requires text or package")
                c.int("timeoutMs")?.let { if (it !in 500..30_000) throw InvalidArgsException("wait timeoutMs must be 500–30000") } }
            "assert" -> if (c["target"] == null && c.str("package") == null) throw InvalidArgsException("assert requires target or package")
            "swipe" -> { for (k in listOf("x1", "y1", "x2", "y2")) if ((c.int(k) ?: -1) < 0) throw InvalidArgsException("swipe requires nonnegative $k")
                c.int("durationMs")?.let { if (it !in 50..5000) throw InvalidArgsException("swipe durationMs must be 50–5000") } }
        }
        c
    }
}
