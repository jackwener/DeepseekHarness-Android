package io.github.jackwener.dsh

import android.content.Intent
import android.app.UiAutomation
import android.provider.Settings
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.jackwener.dsh.core.capability.*
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.data.RuleEntity
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.testing.PhoneFixtureActivity
import io.github.jackwener.dsh.testing.PhoneTestEntryPoint
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real hub, accessibility service, gestures and bundled ML Kit on a connected device. */
@RunWith(AndroidJUnit4::class)
class PhoneToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val entry get() = EntryPointAccessors.fromApplication(context, PhoneTestEntryPoint::class.java)
    private val hub get() = entry.capabilities()
    private fun text(r: ToolResult) = r.content.filterIsInstance<Content.Text>().joinToString("\n") { it.text }
    private fun summary(r: ToolResult) = Json.parseToJsonElement((r.content.first() as Content.Text).text).jsonObject

    @Before fun setup() = runBlocking {
        check(context.packageName.endsWith(".phonecheck")) { "Run with -Pdsh.debugSuffix=.phonecheck; never change an installed user's app settings." }
        entry.settings().setFloatingBall(false)
        entry.settings().setAgentScreen(false)
        for (g in listOf(ToolGroup.Screen, ToolGroup.Apps)) entry.settings().setGroupEnabled(g, true)
        // Instrumentation restarts the target process. Some OEMs leave its previously bound
        // service in the crashed set; rebind only our test service, retaining all other services.
        if (DshAccessibilityService.instance == null) {
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                val component = "${context.packageName}/${DshAccessibilityService::class.java.name}"
                val resolver = context.contentResolver
                val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().split(':').filter { it.isNotBlank() && it != component }
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                delay(250)
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + component).joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } finally { automation.dropShellPermissionIdentity() }
        }
        val until = SystemClock.elapsedRealtime() + 15_000
        while (DshAccessibilityService.instance == null && SystemClock.elapsedRealtime() < until) delay(200)
        assertNotNull("Enable the test package's accessibility service before running connected tests", DshAccessibilityService.instance)
        // MIUI additionally blocks the fixture's background activity start. Bring the test app
        // forward through the instrumentation shell before opening its non-exported fixture.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText()
            }
        hub.onUserPrompt("")
        fixture("form")
    }

    private suspend fun fixture(scenario: String, delayMs: Long = 900) {
        val previous = PhoneFixtureActivity.current
        context.startActivity(Intent(context, PhoneFixtureActivity::class.java).putExtra("scenario", scenario).putExtra("delayMs", delayMs)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val until = SystemClock.elapsedRealtime() + 5_000
        while ((PhoneFixtureActivity.current === previous || PhoneFixtureActivity.current?.intent?.getStringExtra("scenario") != scenario) && SystemClock.elapsedRealtime() < until) delay(100)
        delay(500)
        assertEquals("The native fixture did not reach the screen", context.packageName, DshAccessibilityService.instance!!.snapshot().packageName)
    }
    private suspend fun call(name: String, args: String = "{}", progress: (ToolResult) -> Unit = {}): ToolResult = hub.invoke(buildJsonObject {
        put("tool", name); put("sessionId", ""); put("callId", "device-test-${System.nanoTime()}")
        put("timeoutMs", 120_000)
        put("args", JsonObject(Json.parseToJsonElement(args).jsonObject + ("screen" to JsonPrimitive("main"))))
    }, progress)
    private suspend fun runPlan(commands: String, progress: (ToolResult) -> Unit = {}) = call("phone_run", """{"commands":$commands}""", progress)
    private fun record(name: String, data: JsonObject) {
        File(context.filesDir, "phone-test-results.jsonl").appendText(buildJsonObject { put("test", name); put("data", data) }.toString() + "\n")
    }

    @Test fun sequenceUsesFreshTargetsAndReturnsCompletedSteps() = runBlocking {
        val r = runPlan("""[
            {"op":"type","target":{"resourceId":"phone_fixture_input"},"text":"Redmi native test"},
            {"op":"tap","target":{"text":"Read input"}},
            {"op":"assert","target":{"text":"Input: Redmi native test"}},
            {"op":"tap","target":{"text":"Count once"}},
            {"op":"assert","target":{"text":"Count 1"}}
        ]""")
        assertTrue(text(r), r.ok)
        assertEquals(5, summary(r)["completed"]!!.jsonArray.size)
        assertEquals(1, PhoneFixtureActivity.taps.get())
        record("sequence", summary(r))
    }

    @Test fun malformedLaterStepDoesNotExecuteEarlierStep() = runBlocking {
        val r = runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"key","key":"not-a-key"}]""")
        assertFalse(r.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun ambiguousTargetStopsWithPartialProgress() = runBlocking {
        val r = runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"tap","target":{"text":"Duplicate"}},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals(1, summary(r)["completed"]!!.jsonArray.size)
        record("ambiguous_target", summary(r))
    }

    @Test fun unmetWaitStopsBeforeTheNextAction() = runBlocking {
        val r = runPlan("""[{"op":"wait","text":"never appears","timeoutMs":500},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("condition_timeout", r.errorCode)
        assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun childCapabilityCannotBeBypassedByBatch() = runBlocking {
        entry.settings().setGroupEnabled(ToolGroup.Apps, false)
        try {
            val r = runPlan("""[{"op":"launch","package":"com.android.settings"},{"op":"tap","target":{"text":"Count once"}}]""")
            assertFalse(r.ok); assertEquals("not_authorized", r.errorCode)
            assertEquals(0, PhoneFixtureActivity.taps.get())
        } finally { entry.settings().setGroupEnabled(ToolGroup.Apps, true) }
    }

    @Test fun unattendedWhitelistAppliesToEveryChild() = runBlocking {
        val rule = entry.rules().insert(RuleEntity(specJson = """{"name":"test-only disabled rule","trigger":{"type":"screen","event":"unlock"},"action":{"type":"steps","steps":[]},"allowedTools":["phone_run"]}""", enabled = false))
        try {
            val r = hub.invoke(buildJsonObject {
                put("tool", "phone_run"); put("sessionId", "auto_${rule}_test"); put("timeoutMs", 10_000)
                put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"tap","target":{"text":"Count once"}}]}"""))
            })
            assertFalse(r.ok); assertEquals("not_authorized", r.errorCode)
            assertTrue(text(r), text(r).contains("screen_read"))
            assertEquals(0, PhoneFixtureActivity.taps.get())
        } finally { entry.rules().delete(rule) }
    }

    @Test fun unexpectedAppSwitchStopsRemainingSteps() = runBlocking {
        val r = runPlan("""[{"op":"key","key":"home"},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("app_changed", r.errorCode)
        assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun totalDeadlineReportsUncertainStepWithoutReplaying() = runBlocking {
        val r = hub.invoke(buildJsonObject {
            put("tool", "phone_run"); put("sessionId", ""); put("timeoutMs", 1500)
            put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]}"""))
        })
        assertFalse(r.ok); assertEquals("timeout", r.errorCode)
        delay(1000); assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals("timeout", summary(r)["stopReason"]!!.jsonPrimitive.content)
        record("deadline", summary(r))
    }

    @Test fun cancellationDoesNotExecuteFollowingCommandsAndRetainsProgress() = runBlocking {
        var progress: ToolResult? = null
        val job = launch {
            runPlan("""[{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]""") { progress = it }
        }
        val until = SystemClock.elapsedRealtime() + 5_000
        while ((progress?.let(::summary)?.get("completed")?.jsonArray?.size ?: 0) < 1 && SystemClock.elapsedRealtime() < until) delay(100)
        job.cancelAndJoin(); delay(800)
        assertEquals(1, PhoneFixtureActivity.taps.get())
        assertEquals(1, summary(progress!!)["completed"]!!.jsonArray.size)
        record("cancelled_progress", summary(progress!!))
    }

    @Test fun bridgeCancellationReturnsPartialJsonAndDoesNotReplay() = runBlocking {
        val task = async { hub.onBridgeRequest("capability/invoke", buildJsonObject {
            put("tool", "phone_run"); put("sessionId", ""); put("callId", "test-bridge-cancel"); put("timeoutMs", 30_000)
            put("args", Json.parseToJsonElement("""{"screen":"main","commands":[{"op":"tap","target":{"text":"Count once"}},{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]}"""))
        }).jsonObject }
        withTimeout(5_000) { while (PhoneFixtureActivity.taps.get() == 0) delay(50) }
        delay(700)
        hub.cancel("test-bridge-cancel", "")
        val r = withTimeout(3_000) { task.await() }
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("cancelled", r["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val s = Json.parseToJsonElement(r["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        assertEquals("cancelled", s["stopReason"]!!.jsonPrimitive.content)
        assertEquals(1, s["completed"]!!.jsonArray.size)
        delay(800); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("bridge_cancel", s)
    }

    @Test fun steeringStopsTheOldPlan() = runBlocking {
        val task = async { runPlan("""[{"op":"wait","text":"never appears","timeoutMs":10000},{"op":"tap","target":{"text":"Count once"}}]""") }
        delay(700); hub.onUserPrompt("", "stop the old plan")
        val r = withTimeout(5_000) { task.await() }
        assertFalse(r.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun delayedNativeUpdateIsObservedAndWaitConditionVerified() = runBlocking {
        val start = SystemClock.elapsedRealtime()
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"wait","text":"Delayed ready","timeoutMs":5000},{"op":"assert","target":{"text":"Delayed ready"}}]""")
        assertTrue(text(r), r.ok)
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("elapsed=$elapsed", elapsed in 900..8_000)
        record("delayed_update", buildJsonObject { put("elapsedMs", elapsed) })
    }

    @Test fun explicitWaitObservesLateResultWithoutReplayingTheTap() = runBlocking {
        fixture("form", delayMs = 2_400)
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"wait","text":"Delayed ready","timeoutMs":5000},{"op":"tap","target":{"text":"Count once"}}]""")
        assertTrue(text(r), r.ok)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get()); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("late_update", summary(r))
    }

    @Test fun noObservedEffectStopsBeforeAnotherWrite() = runBlocking {
        fixture("form", delayMs = 3_000)
        val r = runPlan("""[{"op":"tap","target":{"text":"Delayed result"}},{"op":"tap","target":{"text":"Count once"}}]""")
        assertFalse(r.ok); assertEquals("no_effect", r.errorCode)
        assertEquals(1, PhoneFixtureActivity.delayedTaps.get()); assertEquals(0, PhoneFixtureActivity.taps.get())
    }

    @Test fun collectionReadsTheWholeNativeListWithRepeatedValues() = runBlocking {
        fixture("list")
        val r = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"maxPages":30,"maxItems":200}""")
        assertTrue(text(r), r.ok)
        val s = summary(r); val actual = s["items"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals((1..60).map(PhoneFixtureActivity::row), actual)
        assertFalse(s["truncated"]!!.jsonPrimitive.boolean)
        record("collect_forward", s)
        hub.onUserPrompt("")
        val back = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"direction":"up","maxPages":30,"maxItems":200}""")
        assertTrue(text(back), back.ok)
        assertEquals(actual, summary(back)["items"]!!.jsonArray.map { it.jsonPrimitive.content })
        record("collect_backward", summary(back))
    }

    @Test fun collectionHonorsLimits() = runBlocking {
        fixture("list")
        val r = call("phone_collect", """{"target":{"resourceId":"phone_fixture_list"},"maxItems":3,"maxPages":30}""")
        assertTrue(text(r), r.ok)
        val s = summary(r)
        assertEquals(3, s["items"]!!.jsonArray.size)
        assertEquals("output_limit", s["stopReason"]!!.jsonPrimitive.content)
        assertTrue(s["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun invalidCollectionBoundsDoNotReadOrScroll() = runBlocking {
        fixture("list")
        for (args in listOf("""{"maxItems":"invalid"}""", """{"maxPages":1.5}""",
            """{"region":{"left":0,"top":0,"right":1.5,"bottom":200}}""")) {
            val r = call("phone_collect", args)
            assertFalse(text(r), r.ok); assertEquals("invalid_args", r.errorCode)
        }
        val r = call("screen_read")
        assertTrue(r.observation!!.frame.elements.any { it.text == "Row 01" })
    }

    @Test fun collectionPreservesLongNativeText() = runBlocking {
        fixture("longtext")
        val r = call("phone_collect", """{"maxPages":1,"maxChars":5000}""")
        assertTrue(text(r), r.ok)
        assertTrue(text(r), summary(r)["items"]!!.jsonArray.any { it.jsonPrimitive.content == PhoneFixtureActivity.LONG_TEXT })
    }

    @Test fun ocrCoordinatesAreValidatedAndIdsAreNeverReused() = runBlocking {
        fixture("canvas")
        val first = call("screen_read", """{"ocr":true}""")
        assertTrue(text(first), first.ok)
        val id = first.observation!!.textIds.entries.singleOrNull { "TARGET ALPHA" in it.value.text }?.key
        assertNotNull(text(first), id)
        val second = call("screen_read", """{"ocr":true}""")
        assertFalse(second.observation!!.textIds.containsKey(id))
        val current = second.observation!!.textIds.entries.single { "TARGET ALPHA" in it.value.text }.key
        val staleId = call("screen_tap", """{"node":"$id"}""")
        assertFalse(staleId.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("TARGET BRAVO") }
        delay(200)
        val staleFrame = call("screen_tap", """{"node":"$current"}""")
        assertFalse(staleFrame.ok); assertEquals(0, PhoneFixtureActivity.taps.get())
        val fresh = call("screen_read", """{"ocr":true}""")
        val newId = fresh.observation!!.textIds.entries.single { "TARGET BRAVO" in it.value.text }.key
        val tap = call("screen_tap", """{"node":"$newId"}""")
        assertTrue(text(tap), tap.ok); assertEquals(1, PhoneFixtureActivity.taps.get())
        record("ocr_validation", buildJsonObject { put("oldIdRejected", true); put("changedFrameRejected", true); put("freshTargetTapped", true) })
    }

    @Test fun movingCanvasDoesNotIssueOcrCoordinates() = runBlocking {
        fixture("canvas")
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("", true) }
        delay(200)
        val start = SystemClock.elapsedRealtime()
        val r = call("screen_read", """{"ocr":true}""")
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue(text(r), r.observation!!.textIds.isEmpty())
        assertNotNull(r.observation!!.stopReason)
        assertTrue("OCR wait exceeded its bound: $elapsed", elapsed < 10_000)
        record("moving_canvas", buildJsonObject { put("elapsedMs", elapsed); put("coordinatesIssued", 0) })
    }

    @Test fun ocrRetriesWhenCanvasChangesDuringRecognition() = runBlocking {
        fixture("canvas")
        val task = async { call("screen_read", """{"ocr":true}""") }
        // Between the initial samples and post-recognition check, including screenshot throttling.
        delay(1_850)
        instrumentation.runOnMainSync { PhoneFixtureActivity.current!!.updateCanvas("TARGET BRAVO") }
        val r = task.await()
        assertTrue(text(r), r.ok)
        val lines = r.observation!!.textIds.values.map { it.text }
        assertTrue(text(r), lines.any { "TARGET BRAVO" in it })
        assertFalse(lines.any { "TARGET ALPHA" in it })
        record("ocr_changed_during_read", buildJsonObject { put("freshFrameReturned", true) })
    }

    @Test fun realSettingsAppCanBeReadAndCollected() = runBlocking {
        val launch = runPlan("""[{"op":"launch","package":"com.android.settings"},{"op":"assert","package":"com.android.settings"}]""")
        assertTrue(text(launch), launch.ok)
        val r = call("phone_collect", """{"package":"com.android.settings","maxPages":2,"maxItems":200}""")
        assertTrue(text(r), r.ok)
        assertTrue(summary(r)["items"]!!.jsonArray.isNotEmpty())
        // Record counts only: a real settings page can contain account/device names.
        record("native_settings", buildJsonObject { put("pages", summary(r)["pages"]!!); put("items", summary(r)["items"]!!.jsonArray.size) })
    }

    @Test fun realXiaomiCalculatorExecutesAndVerifiesNativeArithmetic() = runBlocking {
        org.junit.Assume.assumeTrue("Xiaomi Calculator is needed for this OEM integration test",
            context.packageManager.getLaunchIntentForPackage("com.miui.calculator") != null)
        val open = runPlan("""[{"op":"launch","package":"com.miui.calculator"},{"op":"assert","package":"com.miui.calculator"}]""")
        assertTrue(text(open), open.ok)
        // Clear only the current scratch expression, not calculation history or app data.
        val clear = call("screen_tap", """{"text":"清除"}"""); assertTrue(text(clear), clear.ok)
        hub.onUserPrompt("")
        val r = runPlan("""[
            {"op":"tap","target":{"resourceId":"digit_7"}},
            {"op":"tap","target":{"resourceId":"op_add"}},
            {"op":"tap","target":{"resourceId":"digit_5"}},
            {"op":"tap","target":{"resourceId":"btn_equal_s"}},
            {"op":"assert","target":{"resourceId":"result","text":"= 12"}}
        ]""")
        assertTrue(text(r), r.ok)
        record("native_calculator", buildJsonObject { put("expression", "7+5"); put("result", "12"); put("completed", summary(r)["completed"]!!) })
    }

    @Test fun compareSingleAndCompoundNativeDispatch() = runBlocking {
        val single = mutableListOf<Long>(); val batch = mutableListOf<Long>()
        repeat(3) {
            fixture("form"); hub.onUserPrompt("")
            var start = SystemClock.elapsedRealtime()
            repeat(4) { val r = call("screen_tap", """{"text":"Count once"}"""); assertTrue(text(r), r.ok) }
            single += SystemClock.elapsedRealtime() - start
            fixture("form"); hub.onUserPrompt("")
            start = SystemClock.elapsedRealtime()
            val r = runPlan("[" + List(4) { """{"op":"tap","target":{"text":"Count once"}}""" }.joinToString(",") + "]")
            assertTrue(text(r), r.ok); assertEquals(4, PhoneFixtureActivity.taps.get())
            batch += SystemClock.elapsedRealtime() - start
        }
        record("native_dispatch_benchmark", buildJsonObject {
            put("singleMs", JsonArray(single.map(::JsonPrimitive))); put("batchMs", JsonArray(batch.map(::JsonPrimitive)))
            put("singleToolCalls", 4); put("batchToolCalls", 1); put("includesModelLatency", false)
        })
    }
}
