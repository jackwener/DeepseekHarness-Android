package io.github.jackwener.dsh.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import io.github.jackwener.dsh.core.capability.Frame
import io.github.jackwener.dsh.core.capability.UiElement
import io.github.jackwener.dsh.core.trigger.TriggerEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Screen reading and input. Also reports foreground-app changes for "app opened" automations
 * and for the quick panel's "current page" context.
 */
class DshAccessibilityService : AccessibilityService() {

    /** One shared thread for screenshot callbacks (a new executor per capture leaked a thread each time). */
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val changes = ConcurrentHashMap<Int, Long>()

    /** Only events for the requested display affect its wait; a busy main screen cannot stall an agent screen. */
    fun lastContentChange(displayId: Int): Long = changes[displayId] ?: 0L

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; shotExecutor.shutdown(); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) && event.packageName?.toString() != SYSTEM_UI) {
            val display = if (Build.VERSION.SDK_INT >= 33) event.displayId else if (Build.VERSION.SDK_INT >= 30) {
                // Older Android has no event.displayId: resolve its window, never broadcast to all displays.
                val all = windowsOnAllDisplays
                (0 until all.size()).firstOrNull { i -> all.valueAt(i).any { it.id == event.windowId } }?.let(all::keyAt)
            } else Display.DEFAULT_DISPLAY
            if (display != null && display >= 0) {
                // Events can be batched by notificationTimeout. Their original uptime avoids
                // adding that delivery delay again to the post-action quiet interval.
                val at = SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)
                changes.merge(display, at, ::maxOf)
            }
        }
        // A click on the user's own screen: what opens there next is the user's doing (AgentScreen.pullBack).
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val pkg = event.packageName?.toString()
            if (pkg != packageName && clickedOnMain(event, pkg)) lastMainClickAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == _foreground.value || pkg == SYSTEM_UI || pkg in imePackages()) return
        // Only a real app window counts as "opened": dialogs, the shade, keyboards and overlays from
        // other packages also send state changes.
        val active = runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) } }.getOrNull()
        val activePkg = active?.root?.packageName?.toString()
        if (activePkg != null && activePkg != pkg) return
        _foreground.value = pkg
        TriggerEvents.appOpened(pkg)
    }

    /** The event's display is API 33+; before that, a click counts as the user's when its app has a window on the main screen. */
    private fun clickedOnMain(event: AccessibilityEvent, pkg: String?): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> event.displayId == Display.DEFAULT_DISPLAY
        Build.VERSION.SDK_INT >= 30 -> runCatching {
            windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty().any { it.root?.packageName?.toString() == pkg }
        }.getOrDefault(false)
        else -> false
    }

    private fun imePackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Snapshot of a screen as a flat element list (parallel to the live nodes). App windows come first
     * (the focused one names the package); system UI (status bar, shade) follows, marked as such;
     * keyboards, accessibility overlays and DSH's own windows (floating ball, quick panel) are left out
     * unless DSH is the only app on screen.
     */
    fun snapshot(displayId: Int = Display.DEFAULT_DISPLAY): ScreenSnapshot {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val elements = ArrayList<UiElement>()
        if (Build.VERSION.SDK_INT < 30) {
            val root = rootInActiveWindow
            root?.let { walk(it, 0, false, nodes, elements) }
            return ScreenSnapshot(displayId, root?.packageName?.toString(), elements, nodes)
        }
        val wins = windowsOnAllDisplays.get(displayId).orEmpty().mapNotNull { w -> w.root?.let { w to it } }
        val apps = wins.filter { it.first.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val others = apps.filter { it.second.packageName?.toString() != packageName }
        val shownApps = (others.ifEmpty { apps }).sortedByDescending { it.first.isActive || it.first.isFocused }
        val system = wins.filter { it.first.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.second.packageName?.toString() != packageName }
        shownApps.forEach { (_, root) -> walk(root, 0, false, nodes, elements) }
        system.forEach { (_, root) -> walk(root, 0, true, nodes, elements) }
        val pkg = shownApps.firstOrNull()?.second?.packageName?.toString() ?: wins.firstOrNull()?.second?.packageName?.toString()
        return ScreenSnapshot(displayId, pkg, elements, nodes)
    }

    /**
     * Presses the keyboard's action key (Enter / Search / Go / Send) in the focused field: the
     * accessibility IME-enter action (API 30+), then the accessibility service's own input connection
     * (API 33+), then DSH 输入法 when it is the current keyboard. False when none applied.
     */
    fun pressImeAction(search: Boolean): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (Build.VERSION.SDK_INT >= 30 && focused != null &&
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.let { im ->
            val ic = im.currentInputConnection
            if (ic != null) {
                val declared = (im.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val action = when {
                    search -> EditorInfo.IME_ACTION_SEARCH
                    declared != EditorInfo.IME_ACTION_NONE && declared != EditorInfo.IME_ACTION_UNSPECIFIED -> declared
                    else -> EditorInfo.IME_ACTION_DONE
                }
                ic.performEditorAction(action); return true // void: the editor gets it if a field is connected
            }
        }
        return DshInputMethodService.instance?.editorAction(search) == true
    }

    /** Deletes the character before the cursor in the focused field (API 33+ input connection, else set text). */
    fun deleteBackward(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputConnection?.let { it.deleteSurroundingText(1, 0); return true }
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val t = n.text?.toString() ?: return false
        if (t.isEmpty() || n.isPassword) return false
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t.dropLast(1))
        })
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, system: Boolean, out: MutableList<AccessibilityNodeInfo>, els: MutableList<UiElement>) {
        if (!n.isVisibleToUser) return
        val rawText = n.text?.toString()
        val rawDesc = n.contentDescription?.toString()
        val text = rawText?.take(120)
        val desc = rawDesc?.take(120)
        val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        var d = depth
        if (interesting && depth < 40) {
            out += n
            val r = Rect().also(n::getBoundsInScreen)
            val flags = buildList {
                if (n.isClickable) add("click"); if (n.isLongClickable) add("long"); if (n.isEditable) add("edit")
                if (n.isScrollable) add("scroll"); if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (!n.isEnabled) add("disabled"); if (n.isFocused) add("focused")
            }
            val label = if (n.isPassword) null else rawText?.takeIf { it.isNotBlank() } ?: rawDesc
            els += UiElement(n.className?.toString()?.substringAfterLast('.') ?: "View", text, desc, n.viewIdResourceName?.substringAfter(":id/"),
                r.centerX(), r.centerY(), depth, flags, n.isEditable, system, r.left, r.top, r.right, r.bottom,
                label?.takeIf { it.length > 120 }?.take(8192), (label?.length ?: 0) > 8192)
            d = depth + 1
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d, system, out, els) }
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60, displayId: Int = Display.DEFAULT_DISPLAY) =
        gesture(Path().apply { moveTo(x, y) }, durationMs, displayId)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, displayId: Int = Display.DEFAULT_DISPLAY) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs, displayId)

    private suspend fun gesture(path: Path, durationMs: Long, displayId: Int): Boolean = suspendCancellableCoroutine { cont ->
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        val ok = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    suspend fun screenshot(displayId: Int = Display.DEFAULT_DISPLAY): Bitmap? = capture(displayId).bitmap

    /** Screenshot plus the failure code (ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = FLAG_SECURE on Android 14+). */
    class Capture(val bitmap: Bitmap?, val errorCode: Int?)

    suspend fun capture(displayId: Int = Display.DEFAULT_DISPLAY): Capture {
        if (Build.VERSION.SDK_INT < 30) return Capture(null, null)
        // DSH's own overlays (ball, status capsule, control border) never end up in a picture of the user's screen.
        if (displayId == Display.DEFAULT_DISPLAY) return FloatingBallService.withoutOverlays { shot(displayId) }
        return shot(displayId)
    }

    @RequiresApi(30)
    private suspend fun shot(displayId: Int): Capture =
        suspendCancellableCoroutine { cont ->
            takeScreenshot(displayId, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(Capture(bmp, null)) { _, capture, _ -> capture.bitmap?.recycle() }
                    else bmp?.recycle()
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(Capture(null, errorCode)) }
            })
        }

    /** The app in front of [displayId], from its window list only (no tree walk). */
    fun packageOn(displayId: Int): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val apps = runCatching { windowsOnAllDisplays.get(displayId).orEmpty() }.getOrDefault(emptyList()).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return (apps.firstOrNull { it.isActive || it.isFocused } ?: apps.firstOrNull())?.root?.packageName?.toString()
    }

    /**
     * Android 13+: commit text through the accessibility service's own input connection
     * (flagInputMethodEditor) — works in most editors without switching keyboards.
     */
    fun commitViaA11yInput(text: String, replace: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val ic = inputMethod?.currentInputConnection ?: return false
        if (replace) { ic.performContextMenuAction(android.R.id.selectAll); ic.commitText("", 1, null) }
        ic.commitText(text, 1, null)
        return true
    }

    companion object {
        @Volatile var instance: DshAccessibilityService? = null
            private set
        private const val SYSTEM_UI = "com.android.systemui"
        private val _foreground = MutableStateFlow<String?>(null)
        /** elapsedRealtime of the user's last click on the main screen (Android 11+). */
        @Volatile var lastMainClickAt = 0L
            private set
        val foregroundPackage: StateFlow<String?> = _foreground
    }
}

class ScreenSnapshot(val displayId: Int, val packageName: String?, val elements: List<UiElement>, val nodes: List<AccessibilityNodeInfo>) {
    /** Plain numbered listing (no stable ids), e.g. for the quick panel's "current page" context. */
    val text: String by lazy { Frame(0, packageName, elements, elements.indices.map { it.toString() }).render() }
}
