package io.github.jackwener.dsh.core.capability.tools

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.ActionGuard
import io.github.jackwener.dsh.core.capability.ActionOutcome
import io.github.jackwener.dsh.core.capability.AgentScreen
import io.github.jackwener.dsh.core.capability.AppKnowledge
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Frame
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.LimitNotice
import io.github.jackwener.dsh.core.capability.OcrLine
import io.github.jackwener.dsh.core.capability.OutwardAction
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.SchemaBuilder
import io.github.jackwener.dsh.core.capability.ScreenDiff
import io.github.jackwener.dsh.core.capability.ScreenOcr
import io.github.jackwener.dsh.core.capability.ScreenObservation
import io.github.jackwener.dsh.core.capability.ScreenSettling
import io.github.jackwener.dsh.core.capability.VisualStamp
import io.github.jackwener.dsh.core.capability.visualStamp
import io.github.jackwener.dsh.core.capability.ScreenTarget
import io.github.jackwener.dsh.core.capability.StableIds
import io.github.jackwener.dsh.core.capability.TextInput
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolDescriptor
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.core.session.TakeoverAnswer
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.ScreenSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * Reading and operating a screen through the accessibility service (Shizuku `input` as fallback):
 * the conversation's agent screen when it has one (AgentScreen), else the user's screen. Element ids
 * are stable across reads, reads after an action only send what changed, and every action reports
 * its measured effect (docs/AGENT-SCREEN-V2.md §3).
 */
@Singleton
class ScreenTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
    private val textInput: TextInput,
    private val sessions: dagger.Lazy<SessionStore>,
    private val agent: AgentScreen,
    private val ocr: ScreenOcr,
    private val knowledge: AppKnowledge,
    private val guard: ActionGuard,
) : ToolSet {
    /** What the model has seen of one screen of one conversation. */
    private class View {
        val ids = StableIds()
        /** The last read sent to the model: the base of the next diff. */
        @Volatile var shown: Frame? = null
        @Volatile var reads = 0
        @Volatile var ocr: List<OcrLine> = emptyList()
        var ocrIds: Map<String, OcrLine> = emptyMap()
        var ocrTargets: Map<String, VisualStamp> = emptyMap()
        var ocrStamp: VisualStamp? = null
        var ocrTree: Int? = null
        var ocrAt = 0L
        var nextTextId = 1
        var ocrError: String? = null
        @Volatile var lastPackage: String? = null
        fun clearOcr() { ocr = emptyList(); ocrIds = emptyMap(); ocrTargets = emptyMap(); ocrStamp = null; ocrTree = null; ocrError = null }
    }
    private val views = ConcurrentHashMap<String, View>()
    private fun view(call: CallContext, d: Int) = views.getOrPut("${call.sessionId.substringBefore('/')}|$d") { View() }

    /** A live snapshot with stable ids assigned. */
    private class Read(var snap: ScreenSnapshot, var frame: Frame)

    private fun svc() = DshAccessibilityService.instance ?: throw PermissionMissingException("Accessibility service (DSH screen control)")

    private fun read(call: CallContext, d: Int): Read {
        val snap = svc().snapshot(d)
        return Read(snap, view(call, d).ids.assign(snap.packageName, snap.elements))
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) { if (cur == null || cur!!.isClickable) return cur; cur = cur!!.parent }
        return null
    }

    private suspend fun settle(call: CallContext, t: ScreenTarget, before: Frame?, requireChange: Boolean = true): ScreenSettling.Result {
        val a11y = svc()
        return ScreenSettling.await(before?.layoutFingerprint ?: read(call, t.displayId).frame.layoutFingerprint,
            requireChange, SystemClock::elapsedRealtime, {
                call.checkCurrent()
                val f = read(call, t.displayId).frame
                ScreenSettling.Sample(f.layoutFingerprint, a11y.lastContentChange(t.displayId))
            }, limitMs = minOf(1_800, (call.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1)))
    }

    private fun observation(call: CallContext, t: ScreenTarget, r: Read, stop: String? = null): ScreenObservation {
        val m = metrics(t.displayId)
        val v = view(call, t.displayId)
        return ScreenObservation(r.frame, t.displayId, m.widthPixels, m.heightPixels, v.ocr, stop ?: v.ocrError, v.ocrIds)
    }

    private fun metrics(displayId: Int): DisplayMetrics = DisplayMetrics().also { m ->
        val dm = context.getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(displayId) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") d.getRealMetrics(m)
    }

    private fun where(t: ScreenTarget) = if (t.agent) "agent screen" else "user's screen"

    private fun header(f: Frame, t: ScreenTarget, g: Int) = metrics(t.displayId).let {
        "App: ${f.packageName} · read #$g · ${where(t)} ${it.widthPixels}x${it.heightPixels} px"
    }

    private fun body(f: Frame, max: Int): String {
        val text = f.render().ifBlank { return "(no readable elements; try screen_capture, or screen_read with ocr: true)" }
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it > 0 } ?: max
        val hidden = text.substring(cut).count { it == '\n' }
        return text.substring(0, cut) + "\n…(truncated: $hidden more elements; use screen_scroll or screen_read for the full list)"
    }

    /**
     * Sends [r] in full and makes it the base of the next diff. Adds recognized text when the tree
     * has too little ([forceOcr]: always), and app knowledge the first time the conversation is in an app.
     */
    private suspend fun full(call: CallContext, t: ScreenTarget, r: Read, max: Int, forceOcr: Boolean): String {
        val v = view(call, t.displayId)
        val recognized = ocrSection(call, t, r, v, forceOcr)
        val g = ++v.reads
        if (r.frame.packageName != null || r.frame.elements.isNotEmpty()) v.shown = r.frame
        return "${header(r.frame, t, g)}\n${body(r.frame, max)}" + recognized + visit(call, t, r.frame)
    }

    /** Text recognition fallback (§3.3); also resets the `t` ids when not run. */
    private suspend fun ocrSection(call: CallContext, t: ScreenTarget, r: Read, v: View, force: Boolean): String {
        v.clearOcr()
        if (!force && r.frame.readableCount >= ScreenOcr.SPARSE) return ""
        try {
            val accepted = withTimeoutOrNull(8_000) {
                repeat(2) {
                    // A quiet tree alone is insufficient for a canvas: compare two captured frames as well.
                    settle(call, t, r.frame, requireChange = false)
                    val fresh = read(call, t.displayId)
                    r.snap = fresh.snap; r.frame = fresh.frame
                    val a = screenshot(t) ?: return@withTimeoutOrNull false
                    val stamp = try { if (isBlank(a)) return@withTimeoutOrNull false; a.visualStamp() } finally { a.recycle() }
                    delay(120)
                    val b = screenshot(t) ?: return@withTimeoutOrNull false
                    val before = b.visualStamp()
                    if (!stamp.same(before) || read(call, t.displayId).frame.layoutFingerprint != r.frame.layoutFingerprint) {
                        b.recycle(); return@repeat
                    }
                    // ScreenOcr owns b until ML Kit completes, including cancellation.
                    val recognized = ocr.read(b)
                    val c = screenshot(t) ?: return@withTimeoutOrNull false
                    val unchanged = try {
                        before.same(c.visualStamp()) && recognized.lines.indices.all { i ->
                            recognized.targets[i].same(c.visualStamp(recognized.lines[i]), .01)
                        }
                    } finally { c.recycle() }
                    if (!unchanged || read(call, t.displayId).frame.layoutFingerprint != r.frame.layoutFingerprint) return@repeat
                    v.ocr = recognized.lines
                    val ids = recognized.lines.map { "t${v.nextTextId++}" }
                    v.ocrIds = ids.zip(recognized.lines).toMap()
                    v.ocrTargets = ids.zip(recognized.targets).toMap()
                    v.ocrStamp = before; v.ocrTree = r.frame.layoutFingerprint; v.ocrAt = SystemClock.elapsedRealtime()
                    return@withTimeoutOrNull true
                }
                false
            }
            if (accepted != true) {
                v.clearOcr(); v.ocrError = "ocr_unstable_or_unavailable"
                val fresh = read(call, t.displayId); r.snap = fresh.snap; r.frame = fresh.frame
                return "\n(Text recognition did not obtain a consistent readable frame; no text ids were issued. Read again after the page settles.)"
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            v.clearOcr(); v.ocrError = "ocr_failed"
            return "\n(Text recognition failed: ${e.message}; no text ids were issued.)"
        }
        if (v.ocr.isEmpty()) return "\n-- text recognized from the screenshot: none --"
        return "\n-- text recognized from a verified screenshot (text ids expire when the page changes) --\n" +
            v.ocrIds.entries.joinToString("\n") { (id, l) -> "[$id] \"${l.text}\" @${l.x},${l.y}" }
    }

    /** First time in an app: its usage knowledge, if any. The agent screen also mutes apps that arrive on it. */
    private suspend fun visit(call: CallContext, t: ScreenTarget, f: Frame): String {
        val v = view(call, t.displayId)
        val pkg = f.packageName ?: return ""
        if (pkg == v.lastPackage) return ""
        v.lastPackage = pkg
        if (t.agent) agent.mute(call.sessionId, pkg)
        return knowledge.firstVisitNotice(call.sessionId, pkg)?.let { "\n\n$it" } ?: ""
    }

    private suspend fun screenshot(t: ScreenTarget): Bitmap? =
        if (t.agent) agent.frame(t.displayId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        else DshAccessibilityService.instance?.let { capture(it, t.displayId).bitmap }

    /** Accessibility screenshot, retried once when the previous one was less than a second ago. */
    private suspend fun capture(a11y: DshAccessibilityService, d: Int): DshAccessibilityService.Capture {
        val shot = a11y.capture(d)
        if (shot.bitmap != null || shot.errorCode != ERROR_INTERVAL_TOO_SHORT) return shot
        delay(1_100)
        return a11y.capture(d)
    }

    /**
     * The read after an action: effects (no change, rate-limit message, another app, an app pulled
     * back from the user's screen), then only the differences from the read the model last saw,
     * unless the page changed.
     */
    private suspend fun after(call: CallContext, t: ScreenTarget, before: Frame?, done: String, watch: AgentScreen.Watch? = null): ToolResult {
        val settled = if (DshAccessibilityService.instance != null) settle(call, t, before) else null
        val spill = agent.pullBack(call.sessionId, watch)
        if (t.agent && agent.showsProtected(t.displayId)) return protectedScreen(call, t, "screen")
        if (DshAccessibilityService.instance == null) return ToolResult.text(done)
        val r = read(call, t.displayId)
        val v = view(call, t.displayId)
        // A custom-drawn screen has (almost) no tree: whether it changed is judged by its recognized text.
        val sparse = r.frame.readableCount < ScreenOcr.SPARSE
        val textBefore = v.ocr.map { it.text }
        val shown = v.shown
        // Diffs leave out the status bar and shade: when the shade opened or closed, the full read shows it.
        val shade = before != null && before.systemUiChanged(r.frame)
        val screen = if (shown != null && !sparse && !shade) {
            val diff = ScreenDiff.of(shown, r.frame)
            if (diff.pageChanged) null
            else {
                val g = ++v.reads
                v.shown = r.frame
                val stale = if (v.ocr.isNotEmpty()) "\nText ids (t…) from earlier reads are no longer valid; use screen_read with ocr: true for new ones." else ""
                v.clearOcr()
                (if (diff.empty) "Screen unchanged since read #${g - 1} (read #$g)."
                else "Screen now (read #$g) — changes since read #${g - 1}; everything else is unchanged:\n" + diff.render(shown, r.frame).trimEnd()) +
                    stale + visit(call, t, r.frame)
            }
        } else null
        val screenText = screen ?: "Screen now — ${full(call, t, r, 6000, forceOcr = false)}"
        val noEffect = before != null && before.fingerprint == r.frame.fingerprint && !shade && spill == null &&
            (!sparse || (textBefore.isNotEmpty() && textBefore == v.ocr.map { it.text }))
        val effects = buildList {
            if (noEffect) add("Effect: nothing changed on screen.")
            LimitNotice.find(before, r.frame)?.let {
                add("Effect: the app says \"$it\" — it is limiting these actions. Stop repeating them and tell the user.")
            }
            val pkg = r.frame.packageName
            if (before != null && pkg != before.packageName && pkg != null)
                add("Effect: switched to ${agent.label(pkg)} ($pkg).")
            spill?.let(::add)
        }
        return ToolResult.text((listOf(done) + effects + screenText).joinToString("\n"))
            .withOutcome(if (noEffect) ActionOutcome.NoEffect else ActionOutcome.Effective, r.frame.fingerprint)
            .withObservation(observation(call, t, r, when {
                LimitNotice.find(before, r.frame) != null -> "app_rate_limited"
                spill != null -> "app_moved_to_another_screen"
                settled?.quiet == false -> "screen_unstable"
                else -> null
            }))
    }

    private companion object {
        const val ERROR_INTERVAL_TOO_SHORT = 3 // AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        const val ERROR_SECURE_WINDOW = 6 /* AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34) */
        const val KINDS = "login, captcha, payment, secure_page, choose, other"
        fun screenshotError(code: Int?) = when (code) {
            null -> "screenshots need Android 11+"
            1 -> "internal error"; 2 -> "accessibility screenshots are not enabled for DSH (re-enable the DSH accessibility service)"
            ERROR_INTERVAL_TOO_SHORT -> "called too often; wait a second"
            4 -> "invalid display"; 5 -> "invalid window"
            else -> "error code $code"
        }

        /**
         * Before Android 14 a FLAG_SECURE window is captured as pure black. Dark UIs are near-black but not
         * uniformly zero, so require every sample on a fine grid to be (almost) exactly black.
         */
        fun isBlank(bmp: Bitmap): Boolean {
            for (y in 0 until 32) for (x in 0 until 32) {
                val p = bmp.getPixel(x * (bmp.width - 1) / 31, y * (bmp.height - 1) / 31)
                if ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff) > 6) return false
            }
            return true
        }
    }

    /** A protected (FLAG_SECURE) page cannot be seen, so it is not operated blind: it goes to the user. */
    private suspend fun protectedScreen(call: CallContext, t: ScreenTarget, toolName: String): ToolResult {
        val pkg = DshAccessibilityService.instance?.snapshot(t.displayId)?.packageName ?: "this app"
        if (call.progress != null) return ToolResult.error("needs_user", "The page is protected; the compound operation stopped. Use hand_to_user, then inspect the screen before planning any remaining steps.")
        return takeover(call, t, toolName, "${agent.label(pkg)} 的当前页面禁止截图（支付、密码等安全页面），需要你在手机上完成这一步。", "secure_page",
            "The screen of $pkg is protected (screenshots are blocked)")
    }

    /**
     * Hands a step to the user (§2): card 我来处理 / 跳过; 我来处理 moves the agent screen's top task to
     * the user's screen (a step on the user's screen [t] is already in front of them); then 处理好了
     * (moved back, read again) / 我自己做完了 / 不做了. The agent screen is kept while the card waits;
     * a card that times out leaves the app with the user.
     */
    private suspend fun takeover(call: CallContext, t: ScreenTarget, toolName: String, reason: String, kind: String, why: String = "This step needs the user"): ToolResult {
        val sid = call.sessionId
        if (call.unattended || sid.isEmpty()) return ToolResult.error("needs_user",
            "$why ($reason). Automations cannot hand steps to the user; stop and report this.")
        val answer = agent.holding(sid) {
            try {
                call.waitFor("handle it on the phone") {
                    sessions.get().requestTakeover(sid, toolName, reason, kind) { t.agent && agent.handToUser(sid) != null }
                }
            } catch (e: CancellationException) {
                agent.leaveWithUser(sid)
                throw e
            }
        }
        return when (answer) {
            TakeoverAnswer.Skip, TakeoverAnswer.Start -> ToolResult.text("$why; the user skipped it (did not do it). Continue without it if you can; otherwise stop and summarize.")
            TakeoverAnswer.Abort -> { agent.leaveWithUser(sid); ToolResult.error("user_declined", "$why; the user chose not to do it. End this step and summarize.") }
            TakeoverAnswer.DoneByUser -> { agent.leaveWithUser(sid); ToolResult.text("$why; the user finished the task themselves on their screen. " +
                "Do not operate any more: only read the screen (screen: \"main\") to verify, then report.") }
            TakeoverAnswer.Handled -> {
                val back = agent.takeBack(sid)
                val now = agent.target(sid, if (t.agent) null else "main")
                if (DshAccessibilityService.instance != null) settle(call, now, null, requireChange = false)
                val screen = DshAccessibilityService.instance?.let { "\nScreen now — ${full(call, now, read(call, now.displayId), 6000, forceOcr = false)}" } ?: ""
                ToolResult.text("$why; the user handled it and tapped 处理好了." + (if (back) " The app is back on the agent screen." else "") +
                    " Continue from the screen below; do not ask them to repeat it.$screen")
            }
        }
    }

    private suspend fun shellKey(code: Int, d: Int): Boolean = shizuku.available && shizuku.exec("input -d $d keyevent $code").code == 0

    /** An element (or recognized text line) the model referred to by id or text. */
    private class Hit(val node: AccessibilityNodeInfo?, val x: Int, val y: Int, val label: String?, val note: String? = null)

    /** Resolves `node` (e…, t…, or a bare number) / `text` against the fresh read [r]. Null when neither was given. */
    private suspend fun locate(call: CallContext, d: Int, args: JsonObject, r: Read): Hit? {
        args.str("node")?.takeIf { it.isNotBlank() }?.let { raw ->
            val id = StableIds.normalize(raw)
            val v = view(call, d)
            if (id.startsWith("t")) {
                val line = v.ocrIds[id]
                    ?: throw InvalidArgsException("Unknown text id $raw; call screen_read again (text ids come from text recognition)")
                verifyTextTarget(call, d, r, v, id, line)
                return Hit(null, line.x, line.y, line.text)
            }
            return when (val l = v.ids.resolve(id, r.frame)) {
                is StableIds.Lookup.Found -> hit(r, l.index, null)
                is StableIds.Lookup.Relocated -> hit(r, l.index, "$id was gone; used the matching element now listed as ${l.newId}.")
                StableIds.Lookup.Gone -> throw InvalidArgsException("Element $id is no longer on screen (it changed or disappeared); call screen_read and use a current id.")
                StableIds.Lookup.Unknown -> throw InvalidArgsException("Unknown element id $raw; call screen_read first.")
            }
        }
        val text = args.str("text")?.takeIf { it.isNotBlank() } ?: return null
        val els = r.frame.elements
        val i = els.indexOfFirst { it.text == text || it.desc == text }.takeIf { it >= 0 }
            ?: els.indexOfFirst { (it.label ?: "").contains(text, ignoreCase = true) }.takeIf { it >= 0 }
        if (i != null) return hit(r, i, null)
        val v = view(call, d)
        val entry = v.ocrIds.entries.singleOrNull { it.value.text.contains(text, ignoreCase = true) }
            ?: throw InvalidArgsException("No element with text '$text'; call screen_read")
        val line = entry.value
        verifyTextTarget(call, d, r, v, entry.key, line)
        return Hit(null, line.x, line.y, line.text)
    }

    private suspend fun verifyTextTarget(call: CallContext, d: Int, r: Read, v: View, id: String, line: OcrLine) {
        var valid = v.ocrTree == r.frame.layoutFingerprint && SystemClock.elapsedRealtime() - v.ocrAt <= 15_000
        if (valid) {
            val t = ScreenTarget(d, d != Display.DEFAULT_DISPLAY)
            val bmp = screenshot(t)
            valid = bmp != null && try {
                v.ocrStamp?.same(bmp.visualStamp()) == true && v.ocrTargets[id]?.same(bmp.visualStamp(line), .01) == true &&
                    read(call, d).frame.layoutFingerprint == v.ocrTree
            } finally { bmp?.recycle() }
        }
        call.checkCurrent()
        if (!valid) {
            v.clearOcr()
            throw InvalidArgsException("Text target $id is stale: the screen changed or its capture expired. Nothing was tapped; call screen_read for fresh text ids.")
        }
    }

    private fun hit(r: Read, i: Int, note: String?): Hit {
        val e = r.frame.elements[i]
        return Hit(r.snap.nodes[i], e.x, e.y, e.label, note)
    }

    /** The labelled element nearest to a coordinate tap (for pacing), within 80 px. */
    private fun labelAt(r: Read, x: Int, y: Int): String? = r.frame.elements.filter { it.label != null }
        .minByOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
        ?.takeIf { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= 80 }?.label

    /** A tap that reaches other people: an outward label on something tappable (a plain text label is not). */
    private fun outward(hit: Hit) = OutwardAction.matches(hit.label) && (hit.node == null || clickable(hit.node) != null)

    /**
     * Outward actions (send, post, follow…) in one app are spaced like a person would (§5). Waits
     * before the tap and returns the model-facing note, or null.
     */
    private suspend fun pace(call: CallContext, pkg: String?, hit: Hit): String? {
        if (pkg == null || !outward(hit)) return null
        val budget = call.deadlineMs - SystemClock.elapsedRealtime() - 15_000
        val wait = guard.pacing.waitBefore(pkg, SystemClock.elapsedRealtime()).coerceAtMost(budget.coerceAtLeast(0))
        if (wait < 1_000) return null
        delay(wait)
        return "Waited ${wait / 1000} s before this tap so the app does not take it for automation."
    }

    /** Every screen tool takes `screen`; acting on the agent screen stops while the user has taken it over. */
    private abstract inner class ScreenTool(name: String, risk: Risk, needsUnlock: Boolean, description: String, schema: SchemaBuilder.() -> Unit) :
        Tool(name, ToolGroup.Screen, risk, needsUnlock, description, {
            schema()
            string("screen", "Which screen: \"agent\" = your own background screen (default when this conversation has one), " +
                "\"main\" = the user's screen (for what they are looking at)", enum = listOf("agent", "main"))
        }) {
        abstract suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult

        override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
            val t = agent.target(call.sessionId, args.str("screen"))
            if (t.agent && risk != Risk.read && agent.userTookOver(call.sessionId)) return ToolResult.error("user_took_over",
                "The user took the app over from the agent screen and is using it on their own screen. Do not operate it; wait for their message or ask them.")
            val r = run(args, call, t)
            return t.note?.let { r.prependText(it) } ?: r
        }
    }

    override val tools = listOf(
        object : ScreenTool("screen_read", Risk.read, true,
            "Read what is on screen as a list of UI elements: [e12] Type \"text\" #resource-id {flags} @x,y (center, in screen pixels). " +
                "Ids stay the same across reads while the element is unchanged; use them with screen_tap/screen_type/screen_scroll. " +
                "When the tree has little text (custom-drawn apps, games) recognized text lines [t1]… are added, tappable the same way. " +
                "Prefer this over screenshots.", {
                bool("ocr", "Also recognize text in a screenshot (when elements are missing from the list)")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                if (t.agent && agent.showsProtected(t.displayId)) return protectedScreen(call, t, name)
                val r = read(call, t.displayId)
                val v = view(call, t.displayId)
                val same = v.shown?.fingerprint == r.frame.fingerprint
                val textBefore = v.ocr.map { it.text }
                val text = full(call, t, r, 12000, forceOcr = args.bool("ocr") == true)
                val unchanged = same && textBefore == v.ocr.map { it.text }
                return ToolResult.text(text).withOutcome(if (unchanged) ActionOutcome.ReadUnchanged else ActionOutcome.ReadChanged, r.frame.fingerprint)
                    .withObservation(observation(call, t, r))
            }
        },
        object : ScreenTool("screen_capture", Risk.read, true,
            "Screenshot of the screen as an image. Use only when screen_read is not enough (images, canvas, games). " +
                "The image is downscaled; the caption gives the factor to convert image pixels to the screen pixels that screen_tap/screen_swipe take.", {}) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val d = t.displayId
                val bmp = if (t.agent) {
                    if (agent.showsProtected(d)) return protectedScreen(call, t, name)
                    screenshot(t) ?: throw IllegalStateException("No frame from the agent screen yet; wait a moment and retry")
                } else {
                    val shot = capture(svc(), d)
                    if (shot.errorCode == ERROR_SECURE_WINDOW) return protectedScreen(call, t, name)
                    val b = shot.bitmap ?: throw IllegalStateException("Screenshot failed: ${screenshotError(shot.errorCode)}")
                    val interactive = context.getSystemService(PowerManager::class.java).isInteractive
                    if (Build.VERSION.SDK_INT < 34 && interactive && isBlank(b)) return protectedScreen(call, t, name)
                    b
                }
                val img = Images.encode(bmp)
                val k = bmp.width.toFloat() / img.width
                val caption = (if (img.width == bmp.width) "image ${img.width}x${img.height} = screen pixels"
                    else "image ${img.width}x${img.height} = screen ${bmp.width}x${bmp.height} scaled ${"%.3f".format(1 / k)}. " +
                        "screen_tap/screen_swipe take SCREEN pixels: multiply image coordinates by ${"%.3f".format(k)}.") + " (${where(t)})"
                return ToolResult.image(Images.MIME, Base64.encodeToString(img.bytes, Base64.NO_WRAP), caption)
            }
        },
        object : ScreenTool("screen_tap", Risk.write, true,
            "Tap an element (id from the latest screen read: e12, or a recognized text line: t3), visible text, or screen-pixel coordinates. " +
                "Returns the effect and what changed on screen.", {
                string("node", "Element id from the latest screen read (e12) or recognized text id (t3)")
                string("text", "Visible text/description of the element to tap")
                number("x", "X in screen pixels"); number("y", "Y in screen pixels")
                string("kind", "Tap kind", enum = listOf("tap", "long"))
            }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val d = t.displayId
                val long = args.str("kind") == "long"
                val px = args.int("x"); val py = args.int("y")
                // Coordinates still work through Shizuku alone, without effects (no accessibility tree to compare).
                if (DshAccessibilityService.instance == null && px != null && py != null && shizuku.available)
                    return ToolResult.text(if (tapAt(px.toFloat(), py.toFloat(), long, d)) "Tapped at $px,$py (Shizuku; no accessibility service, so the effect is unknown)" else "Tap was cancelled")
                suspend fun target(r: Read) = locate(call, d, args, r) ?: run {
                    val x = px ?: throw InvalidArgsException("Give node, text, or x/y")
                    val y = py ?: throw InvalidArgsException("missing y")
                    Hit(null, x, y, labelAt(r, x, y))
                }
                var r = read(call, d)
                var hit = target(r)
                // The wait can be long: find the target again on the screen as it is after it.
                val paced = pace(call, r.frame.packageName, hit)?.also { r = read(call, d); hit = target(r) }
                val watch = if (t.agent) agent.watch(call.sessionId) else null
                call.checkCurrent()
                val c = hit.node?.takeIf { it.refresh() }?.let(::clickable)
                var tapped = true
                val done = if (c != null && c.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)) {
                    "Tapped [${hit.label ?: "element"}]"
                } else {
                    val (x, y) = hit.node?.let { n -> Rect().also(n::getBoundsInScreen).let { it.centerX() to it.centerY() } } ?: (hit.x to hit.y)
                    tapped = tapAt(x.toFloat(), y.toFloat(), long, d)
                    if (tapped) "Tapped at $x,$y" + (hit.label?.let { " [$it]" } ?: "") else "Tap was cancelled"
                }
                if (tapped && outward(hit)) r.frame.packageName?.let { guard.pacing.noteDone(it, SystemClock.elapsedRealtime()) }
                val result = after(call, t, r.frame, listOfNotNull(paced, hit.note, done).joinToString("\n"), watch)
                return if (tapped) result else result.failed("gesture_cancelled", "Android cancelled the tap; inspect the screen before retrying.")
            }

            private suspend fun tapAt(x: Float, y: Float, long: Boolean, d: Int): Boolean {
                val a11y = DshAccessibilityService.instance
                return if (a11y != null) a11y.tap(x, y, if (long) 700 else 60, d)
                else if (shizuku.available) shizuku.exec(if (long) "input -d $d swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 700"
                    else "input -d $d tap ${x.toInt()} ${y.toInt()}").code == 0
                else throw PermissionMissingException("Accessibility service (DSH screen control)")
            }
        },
        object : ScreenTool("screen_type", Risk.write, true,
            "Type text into an editable field (element id, or the focused field). Replaces the field's content unless append=true. " +
                "submit=true then presses the keyboard's Enter/Search key. Works in WebView/Flutter fields too (falls back to the DSH keyboard, Shizuku or paste).", {
                string("text", "Text to enter", required = true)
                string("node", "Editable element id from the latest screen read (default: focused field)")
                bool("append", "Append instead of replacing")
                bool("submit", "Press Enter/Search afterwards (for search boxes without a button)")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val d = t.displayId
                val r = read(call, d)
                val hit = locate(call, d, args.only("node"), r)
                if (hit != null && hit.node?.isEditable != true) throw InvalidArgsException("The selected target is not an editable element; tap its current text id to focus it first.")
                val target = hit?.node ?: focusedInput(d, r)
                call.checkCurrent()
                val out = textInput.type(svc(), target, args.str("text") ?: throw InvalidArgsException("missing text"), args.bool("append") == true, d)
                    ?: return ToolResult.error("input_failed", if (target == null) "No focused input field; tap the field first, then call screen_type again."
                        else "The field did not accept text by any method (set text, keyboard, Shizuku, paste). Tap the field and retry, or ask the user to type it.")
                val watch = if (t.agent) agent.watch(call.sessionId) else null
                val submit = if (args.bool("submit") == true) pressEnter(false, d, r) else null
                val submitted = submit?.let { " and pressed $it" } ?: ""
                val result = after(call, t, r.frame, listOfNotNull(hit?.note, "Entered text (${out.method})$submitted" +
                    (out.fieldText?.let { "; the field now shows: \"${it.take(300)}\"" } ?: "")).joinToString("\n"), watch)
                return if (args.bool("submit") == true && submit == null) result.failed("submit_failed", "Text was entered, but Enter failed. Do not type it again; inspect the screen.") else result
            }

            private fun focusedInput(d: Int, r: Read): AccessibilityNodeInfo? =
                if (d == Display.DEFAULT_DISPLAY) svc().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                else r.snap.nodes.firstOrNull { it.isEditable && it.isFocused }
        },
        object : ScreenTool("screen_swipe", Risk.write, true, "Swipe/drag from (x1,y1) to (x2,y2), in screen pixels.", {
            number("x1", "Start X (screen pixels)", required = true); number("y1", "Start Y (screen pixels)", required = true)
            number("x2", "End X (screen pixels)", required = true); number("y2", "End Y (screen pixels)", required = true)
            int("durationMs", "Default 300")
        }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val d = t.displayId
                fun i(k: String) = args.int(k) ?: throw InvalidArgsException("missing $k")
                val before = read(call, d).frame
                val acted = svc().swipe(i("x1").toFloat(), i("y1").toFloat(), i("x2").toFloat(), i("y2").toFloat(), (args.int("durationMs") ?: 300).toLong().coerceIn(50, 5000), d)
                val result = after(call, t, before, if (acted) "Swiped" else "Swipe was cancelled")
                return if (acted) result else result.failed("gesture_cancelled", "Android cancelled the swipe.")
            }
        },
        object : ScreenTool("screen_scroll", Risk.write, true, "Scroll the screen or a scrollable element.", {
            string("direction", "Direction", required = true, enum = listOf("down", "up", "left", "right"))
            string("node", "Scrollable element id (optional)")
        }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val d = t.displayId
                val dir = args.reqStr("direction")
                if (dir !in listOf("down", "up", "left", "right")) throw InvalidArgsException("Unknown scroll direction")
                val r = read(call, d)
                val n = locate(call, d, args.only("node"), r)?.node
                if (n != null && !n.isScrollable) throw InvalidArgsException("Target is not scrollable")
                val acted = if (n != null) {
                    n.performAction(if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                } else {
                    val m = metrics(d)
                    val cx = m.widthPixels / 2f; val cy = m.heightPixels / 2f
                    val dy = m.heightPixels * 0.3f; val dx = m.widthPixels * 0.3f
                    when (dir) {
                        "down" -> svc().swipe(cx, cy + dy, cx, cy - dy, 350, d)
                        "up" -> svc().swipe(cx, cy - dy, cx, cy + dy, 350, d)
                        "right" -> svc().swipe(cx + dx, cy, cx - dx, cy, 350, d)
                        else -> svc().swipe(cx - dx, cy, cx + dx, cy, 350, d)
                    }
                }
                val result = after(call, t, r.frame, if (acted) "Scrolled $dir" else "Scroll did not move (end reached or Android refused it)")
                return if (acted) result else result.withOutcome(ActionOutcome.NoEffect)
            }
        },
        object : ScreenTool("screen_key", Risk.write, true,
            "Press a key. enter/search = the keyboard's action key in the focused field (submit a search box or a form); delete = backspace; " +
                "back/home/recents; notifications/quick_settings open the shade; lock_screen turns the screen off. " +
                "On the agent screen only enter/search/delete/back/home apply.", {
            string("key", "Key", required = true, enum = listOf("enter", "search", "delete", "back", "home", "recents", "notifications", "quick_settings", "lock_screen"))
        }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val key = args.reqStr("key")
                if (key !in listOf("enter", "search", "delete", "back", "home", "recents", "notifications", "quick_settings", "lock_screen")) throw InvalidArgsException("Unknown key")
                val d = t.displayId
                val r = read(call, d)
                val watch = if (t.agent) agent.watch(call.sessionId) else null
                val done: String = when (key) {
                    "enter", "search" -> pressEnter(key == "search", d, r) ?: return ToolResult.error("failed",
                        "Could not press $key: the focused field takes no editor action and Shizuku is not available. Tap the on-screen search/send button instead (screen_read to find it).")
                    "delete" -> when {
                        d == Display.DEFAULT_DISPLAY && svc().deleteBackward() -> "Deleted one character"
                        shellKey(67, d) -> "Pressed delete (Shizuku)"
                        else -> return ToolResult.error("failed", "No focused text field to delete from.")
                    }
                    else -> {
                        val global = when (key) {
                            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
                            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS; "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
                            else -> GLOBAL_ACTION_NOTIFICATIONS
                        }
                        if (d != Display.DEFAULT_DISPLAY) {
                            val code = when (key) { "back" -> 4; "home" -> 3; else -> throw InvalidArgsException("'$key' only works on the user's screen (screen: \"main\")") }
                            if (!shellKey(code, d)) return ToolResult.error("shizuku_unavailable", "Keys on the agent screen need Shizuku.")
                            "Pressed $key"
                        } else if (svc().performGlobalAction(global)) "Pressed $key" else return ToolResult.error("failed", "Android refused '$key'.")
                    }
                }
                if (key == "lock_screen") return ToolResult.text(done)
                return after(call, t, r.frame, done, watch)
            }
        },
        object : ScreenTool("screen_wait", Risk.read, true,
            "Wait until an element with this text appears (or disappears with gone=true), or until an app is in front, instead of sleeping and polling. " +
                "Returns the screen when the condition holds or after the timeout.", {
                string("text", "Visible text/description to wait for")
                string("package", "Wait until this app is in front")
                bool("gone", "Wait for the text to disappear instead")
                int("timeoutMs", "Default 10000, max 30000")
            }) {
            override suspend fun run(args: JsonObject, call: CallContext, t: ScreenTarget): ToolResult {
                val text = args.str("text"); val pkg = args.str("package")
                if (text == null && pkg == null) throw InvalidArgsException("Give text and/or package")
                val gone = args.bool("gone") == true
                val until = SystemClock.elapsedRealtime() + (args.int("timeoutMs") ?: 10_000).coerceIn(500, 30_000)
                while (true) {
                    val snap = svc().snapshot(t.displayId)
                    val hasText = text == null || snap.elements.any { e -> (e.text ?: "").contains(text, true) || (e.desc ?: "").contains(text, true) }
                    val ok = (pkg == null || snap.packageName == pkg) && (if (gone && text != null) !hasText else hasText)
                    if (ok || SystemClock.elapsedRealtime() > until) {
                        val r = Read(snap, view(call, t.displayId).ids.assign(snap.packageName, snap.elements))
                        val result = ToolResult.text("${if (ok) "Condition met" else "Timed out waiting"} — ${full(call, t, r, 6000, forceOcr = false)}")
                            .withOutcome(null, r.frame.fingerprint)
                            .withObservation(observation(call, t, r))
                        return if (ok) result else result.failed("condition_timeout", "The wait condition did not hold before the timeout.")
                    }
                    call.checkCurrent()
                    delay(500)
                }
            }
        },
        object : Tool("hand_to_user", ToolGroup.Screen, Risk.read, false,
            "Hand the current step to the user when only they can or should do it: signing in, a captcha, a payment, a protected page, " +
                "or a choice that is theirs to make. The user sees a card with the reason; the app moves to their screen while they handle it " +
                "and comes back afterwards. Returns whether they handled it (then the screen is read again), finished the task themselves, skipped or stopped.", {
                string("reason", "What the user should do, in the user's language (shown to them), e.g. \"请登录你的淘宝账号\"", required = true)
                string("kind", "Kind of step: $KINDS", required = true, enum = KINDS.split(", "))
                string("screen", "Where the step is: \"agent\" = your background screen (default when this conversation has one), \"main\" = the user's screen",
                    enum = listOf("agent", "main"))
            }, timeoutMs = ToolDescriptor.UNLOCK_TIMEOUT_MS) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult =
                takeover(call, agent.target(call.sessionId, args.str("screen")), name, args.reqStr("reason"), args.str("kind") ?: "other")
        },
    )

    private fun JsonObject.only(vararg keys: String) = JsonObject(filter { it.key in keys })

    /** Enter/Search in the focused field: accessibility IME action, DSH keyboard, then Shizuku keyevent. Returns how, or null. */
    private suspend fun pressEnter(search: Boolean, d: Int, r: Read): String? {
        if (d == Display.DEFAULT_DISPLAY) {
            if (DshAccessibilityService.instance?.pressImeAction(search) == true) return if (search) "Search" else "Enter"
        } else if (Build.VERSION.SDK_INT >= 30) {
            // The agent screen has its own focus: act on its focused field directly.
            val f = r.snap.nodes.firstOrNull { it.isEditable && it.isFocused }
            if (f != null && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return if (search) "Search" else "Enter"
        }
        if (shellKey(66, d)) return "Enter (Shizuku)"
        return null
    }
}
