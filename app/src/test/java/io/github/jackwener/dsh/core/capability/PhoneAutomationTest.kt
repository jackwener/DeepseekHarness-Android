package io.github.jackwener.dsh.core.capability

import io.github.jackwener.dsh.core.capability.tools.parsePhoneSelector
import io.github.jackwener.dsh.core.capability.tools.validatePhoneCommands
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PhoneAutomationTest {
    private fun frame(vararg e: UiElement) = Frame(1, "test.app", e.toList(), e.indices.map { "e${it + 1}" })
    private fun item(text: String, y: Int = 100, id: String? = null) = UiElement("Button", text, null, id, 100, y)

    @Test fun selectorsAreResolvedOnEachNewPage() {
        val selector = PhoneSelector(text = "Next")
        assertEquals("e1", selector.resolve(frame(item("Next"), item("Previous"))))
        assertEquals("e2", selector.resolve(frame(item("Previous"), item("Next"))))
    }
    @Test fun ambiguousLabelsRequireAdditionalEvidence() {
        val f = frame(item("Open", id = "one"), item("Open", id = "two"))
        assertThrows(InvalidArgsException::class.java) { PhoneSelector(text = "Open").resolve(f) }
        assertEquals("e2", PhoneSelector(text = "Open", resourceId = "test.app:id/two").resolve(f))
    }
    @Test fun systemAndDisabledNodesAreNotTargets() {
        assertThrows(InvalidArgsException::class.java) { PhoneSelector(text = "Open").resolve(frame(item("Open").copy(system = true))) }
        assertThrows(InvalidArgsException::class.java) { PhoneSelector(text = "Open").resolve(frame(item("Open").copy(flags = listOf("disabled")))) }
    }
    @Test fun selectorsDoNotGuessPartialMatches() {
        val f = frame(item("Open settings"))
        assertThrows(InvalidArgsException::class.java) { PhoneSelector(text = "Open").resolve(f) }
        assertEquals("e1", PhoneSelector(text = "OPEN", contains = true).resolve(f))
    }
    @Test fun overlapPreservesRepeatedMessagesAndOrder() {
        val p = OrderedPages(false, 30, 1000)
        p.add(listOf("A", "same", "B")); p.add(listOf("same", "B", "same", "C"))
        assertEquals(listOf("A", "same", "B", "same", "C"), p.items)
        assertEquals(0, p.add(listOf("same", "C")))
    }
    @Test fun backwardsCollectionPrependsInReadingOrder() {
        val p = OrderedPages(true, 30, 1000)
        p.add(listOf("D", "E", "F")); p.add(listOf("B", "C", "D", "E")); p.add(listOf("A", "B"))
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), p.items)
    }
    @Test fun limitsDoNotSplitAnItemOrDestroyExistingPages() {
        val p = OrderedPages(false, 3, 7)
        p.add(listOf("aa", "bb")); p.add(listOf("bb", "cc", "dd"))
        assertEquals(listOf("aa", "bb", "cc"), p.items); assertTrue(p.truncated)
        val back = OrderedPages(true, 3, 100)
        back.add(listOf("D", "E")); back.add(listOf("A", "B", "C", "D"))
        assertEquals(listOf("C", "D", "E"), back.items); assertTrue(back.truncated)
    }
    @Test fun motionAndResizeInvalidateLayoutButNotContent() {
        val a = frame(item("A")); val b = frame(item("A", y = 170))
        assertEquals(a.fingerprint, b.fingerprint); assertNotEquals(a.layoutFingerprint, b.layoutFingerprint)
        assertNotEquals(a.layoutFingerprint, frame(item("A").copy(right = 300)).layoutFingerprint)
    }
    @Test fun adaptiveWaitReturnsEarlyForAChangedQuietScreen() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(1, true, { clock }, { ScreenSettling.Sample(2, 0) }, { clock += it })
        assertTrue(r.quiet); assertTrue(r.changed); assertEquals(240L, r.elapsedMs)
    }
    @Test fun delayedChangeIsNotMistakenForNoEffect() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(1, true, { clock }, { ScreenSettling.Sample(if (clock < 900) 1 else 2, if (clock < 900) 0 else 900) }, { clock += it })
        assertTrue(r.quiet); assertTrue(r.changed); assertTrue(r.elapsedMs in 1_140..1_300)
    }
    @Test fun continuousAnimationAndEventsAreBounded() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(0, true, { clock }, { ScreenSettling.Sample(clock.toInt(), clock) }, { clock += it })
        assertFalse(r.quiet); assertEquals(1_800L, r.elapsedMs)
    }
    @Test fun aTransientChangeDoesNotHideADelayedResult() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(1, true, { clock }, {
            ScreenSettling.Sample(when { clock < 80 -> 2; clock < 900 -> 1; else -> 3 }, 0)
        }, { clock += it })
        assertTrue(r.changed); assertTrue(r.quiet); assertTrue(r.elapsedMs in 1_140..1_300)
    }
    @Test fun anUnchangedActionGetsTheObservationWindow() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(1, true, { clock }, { ScreenSettling.Sample(1, 0) }, { clock += it })
        assertFalse(r.changed); assertTrue(r.quiet); assertEquals(1_800L, r.elapsedMs)
    }
    @Test fun unrelatedDisplayEventsDoNotChangeTheSample() = runBlocking {
        var clock = 0L
        val r = ScreenSettling.await(1, false, { clock }, { ScreenSettling.Sample(1, 0) }, { clock += it })
        assertEquals(240L, r.elapsedMs)
    }
    @Test fun frameComparisonRejectsMotionResizeAndSmallTargetChanges() {
        val a = VisualStamp(100, 200, IntArray(100) { 0xffffff })
        assertTrue(a.same(VisualStamp(100, 200, IntArray(100) { 0xfefefe })))
        assertFalse(a.same(VisualStamp(200, 100, IntArray(100) { 0xffffff })))
        assertFalse(a.same(VisualStamp(100, 200, IntArray(100) { if (it < 3) 0 else 0xffffff })))
        assertFalse(a.same(VisualStamp(100, 200, IntArray(100) { if (it < 2) 0 else 0xffffff }), .01))
    }
    @Test fun badLaterCommandsAreRejectedBeforeExecution() {
        for (json in listOf("[]", "[{\"op\":\"tap\",\"target\":{\"text\":\"A\"}},{\"op\":\"shell\"}]",
            "[{\"op\":\"key\",\"key\":\"typo\"}]", "[{\"op\":\"tap\"}]", "[{\"op\":\"wait\",\"text\":\"A\",\"timeoutMs\":99999}]")) {
            assertThrows(InvalidArgsException::class.java) { validatePhoneCommands(Json.parseToJsonElement(json)) }
        }
    }
    @Test fun clearTextAndFreshSelectorsAreSupported() {
        assertEquals(1, validatePhoneCommands(Json.parseToJsonElement("[{\"op\":\"type\",\"text\":\"\",\"target\":{\"resourceId\":\"input\"}}]")).size)
        assertThrows(InvalidArgsException::class.java) { parsePhoneSelector(Json.parseToJsonElement("{\"node\":\"e1\"}")) }
    }
    @Test fun resultDecorationKeepsStructuredObservation() {
        val o = ScreenObservation(frame(item("A")), 0, 100, 200)
        val r = ToolResult.text("ok").withObservation(o).withOutcome(ActionOutcome.Effective).appendText("guard").prependText("main")
        assertSame(o, r.observation); assertSame(o, r.failed("cancelled", "stopped").observation)
    }
    @Test fun interruptionKeepsCompletedItemsAndMarksCollectionTruncated() {
        val r = ToolResult.text("""{"items":["one"],"pages":1,"inFlight":"scroll","stopReason":"running","truncated":false}""")
            .partialFailure("cancelled", "Stopped")
        assertFalse(r.ok); assertEquals("cancelled", r.errorCode)
        val first = (r.content.first() as Content.Text).text
        assertTrue(first.contains("\"stopReason\":\"cancelled\"")); assertTrue(first.contains("\"truncated\":true"))
        assertTrue(first.contains("\"items\":[\"one\"]")); assertTrue(first.contains("\"inFlight\":\"scroll\""))
    }
}
