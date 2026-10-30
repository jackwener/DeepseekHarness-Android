# Native phone automation

`phone_run` and `phone_collect` operate installed Android apps through the same accessibility,
input and optional Shizuku paths as `screen_*`. Both belong to the Screen capability group and
have **write** risk because they can change the visible screen. Neither creates a browser session.
The existing `screen: "main"` / `"agent"` selection still applies.

## Short sequences

Use `phone_run` when the next steps are already known. It accepts 1–30 commands and has one
180-second call budget (the bridge answers before that deadline). Every child rechecks enabled
capabilities, conversation mode, automation whitelist, lock state and the loop guard. An
automation must allow both the container and its individual tools, including `screen_read`.

```json
{
  "screen": "main",
  "commands": [
    {"op": "launch", "package": "com.example.notes"},
    {"op": "tap", "target": {"text": "Search", "role": "Button"}},
    {"op": "type", "target": {"resourceId": "search_input"}, "text": "meeting"},
    {"op": "wait", "text": "Search results", "timeoutMs": 10000},
    {"op": "assert", "target": {"text": "Search results"}}
  ]
}
```

The package and selectors above are examples, not a built-in app integration. Supported operations:

| Operation | Arguments |
| --- | --- |
| `launch` | `package`, optional exported `activity` |
| `tap` | `target`, optional `kind: "long"` |
| `type` | `text` (empty clears), optional `target`, `append`, `submit` |
| `scroll` | `direction`, optional scrollable `target` |
| `swipe` | `x1`, `y1`, `x2`, `y2`, optional `durationMs` |
| `key` | `key`: enter, search, delete, back, home, recents |
| `wait` | `text` and/or `package`, optional `gone`, `timeoutMs` (500–30000) |
| `assert` | `target` and/or `package` |
| `observe` | no additional arguments |

A target combines exact `text`, `resourceId` and/or widget `role`. Optional `contains: true`
matches text case-insensitively. It must identify exactly one enabled element on a fresh screen.
For custom-drawn controls, a unique OCR text match can be used; `ocr: true` at the top level
forces recognition before each step. OCR coordinates are validated again before tapping.

The entire command structure is validated before any action. A failed/ambiguous target, failed
condition, unexpected app or display change, no-effect action, rate-limit notice or unreadable
frame stops the remaining commands. An unobserved effect may proceed only to an explicit `wait`
or `assert`; that condition must pass before any further action. A `launch` explicitly changes
the expected app. A new user
message invalidates the old plan, including after permission waits. The existing user-takeover
and outward-action pacing mechanisms remain in effect.

The result includes completed step numbers and timings, the final screen, or partial progress
and an `inFlight` step on failure/timeout. Completed actions are **not rolled back**. The in-flight
action may already have happened: inspect the screen and plan only the remaining work. There is
no whole-plan retry. This is a sequential convenience tool, not a transaction against an app or
protection against a person changing the screen at exactly the same time.

## Multi-screen collection

```json
{
  "screen": "main",
  "package": "com.example.notes",
  "target": {"resourceId": "notes_list"},
  "direction": "down",
  "maxPages": 10,
  "maxItems": 300,
  "maxChars": 24000
}
```

`phone_collect` reads the first page, then scrolls and consumes fresh observations. A unique
scrollable container supplies the default content region. An explicit region uses screen-pixel
`left`, `top`, `right`, `bottom`; it excludes fixed headers/footers and bounds fallback swipes.
An explicit `target` is freshly resolved on each page. `ocr: true` collects verified screenshot
text instead of native labels. Native labels longer than the compact screen preview are retained,
with an explicit source limit at 8192 characters per element.

Only the longest adjacent suffix/prefix overlap is merged. Equal text elsewhere remains in the
result; collecting upward prepends items in reading order. This is text collection, not stable
database row identity: pages containing only identical values cannot establish further progress.
Stops include two pages with no new content, an ineffective scroll, an unreadable page, app/display
change, timeout and explicit page/item/character limits. Bounds are 30 pages, 1000 items and 60000
characters. `items`, `pages`, `scrolls`, `stopReason` and `truncated` describe the result; always
inspect the stopping reason before claiming a list is complete.

## Settling and OCR

Post-action observations replace the fixed 400 ms sleep with display-specific accessibility
events and a layout fingerprint (including positions). A changed screen can finish after a
240 ms quiet interval; an unchanged/continuously changing screen is bounded by 1800 ms. This
cannot predict arbitrary future network updates: use explicit `wait`/`assert` for their conditions.
Polling does not take a full screenshot on every tick.

OCR first waits for the tree and two image samples to settle, then checks the image and tree
again after recognition. A changed frame gets one retry, within an 8-second OCR budget.
Android screenshot-rate limits are respected. Text ids increase monotonically within a session
display; later reads never assign an old id to a different line. Before a text-id/text-fallback
tap, the full image sample, the target's padded image region, layout and 15-second age limit
must still match. Failure issues no tap and requests a fresh read. Images are released after
recognition completes, including when the coroutine is cancelled.

## Validation

The [Redmi validation record](PHONE-AUTOMATION-VALIDATION.md) contains measured results and scope.

JVM tests cover selectors, ordered overlaps in both directions, bounds, layout motion, visual
comparison, adaptive clocks and complete-plan validation. Existing JavaScript tests cover dynamic
tool registration/permissions; prompt tests verify conditional guidance and no-replay instructions.

Connected tests use a non-exported **debug-only** native fixture plus the real Android Settings
and Xiaomi Calculator apps (the calculator test skips on devices without that OEM package).
They call the production `CapabilityHub`, accessibility service, gestures and bundled ML Kit.
They cover actual input, delayed updates, duplicate labels, cancellation, steering, deadlines,
child capability/automation checks, full forward/backward list collection, long text, OCR target
invalidation, moving canvas and single-call versus compound dispatch timing. The fixture and test
entry point are absent from release builds.

Build the normal native/engine bundles first (see the repository build instructions), then:

```sh
./gradlew -Pdsh.debugSuffix=.phonecheck :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Install the two APKs on an authorized device and run:

```sh
adb shell am instrument -w -r -e class io.github.jackwener.dsh.PhoneToolsDeviceTest \
  io.github.jackwener.dsh.phonecheck.test/androidx.test.runner.AndroidJUnitRunner
```

The suite refuses to run against the normal application id. It uses the instrumentation shell to
rebind only the test package's accessibility service after the runner restarts its process, preserves
other enabled services, and brings the test app forward to avoid OEM background-start restrictions.
Use an unlocked phone, leave its screen available while tests run, and restore the prior accessibility
settings afterward. Do not run another UIAutomator session concurrently: it may suppress accessibility
services. Test metrics in
the test package's `files/phone-test-results.jsonl` contain fixture results and counts for Settings;
they do not include account names or model credentials. Native dispatch measurements exclude
model/network latency and cannot establish an end-to-end LLM speedup.
