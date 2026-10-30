# Native phone validation — 2026-10-31

Baseline: upstream v0.2.5, `833166fd0171f922df17d4178427ea538532070d`.
Physical device: Redmi 23013RK75C, Android 15 / API 35, 1440 × 3200.
The separate `io.github.jackwener.dsh.phonecheck` debug package preserved the existing installed
DSH app. Tests used the production capability hub, native accessibility actions/gestures and
bundled ML Kit on the phone's main display. No emulator or browser was used.

## Results

| Check | Result |
| --- | --- |
| JVM unit tests | 148 passed, zero failures/errors/skips |
| JavaScript Node test runner | 119 passed, zero failures/skips |
| Standalone attachment regression | Passed against the staged engine bundle |
| Debug, instrumentation and Release APK builds | Passed |
| Release isolation | Native phone tools present; debug fixture absent from Release DEX |
| Connected functional suite | 24 passed, zero failures/skips; 128.236 seconds |
| Additional Calculator confirmation | Passed; independently observed `7+5` and `= 12` in native UI |
| Lint | 7 existing error occurrences, matching clean upstream v0.2.5; zero new errors |
| Whitespace validation | `git diff --check` passed |

The existing lint errors are `LocalContextGetResourceValueCall` in `AutomationScreen.kt` and
`ProvidersUi.kt`, and `PermissionImpliesUnsupportedChromeOsHardware` in the main manifest.
There are six unique findings and seven occurrences because one provider finding appears twice.
Lint is **not** reported as passing; these unrelated upstream errors were not suppressed.

## Connected coverage

The 24 tests exercise:

- Fresh selectors, actual text input, clicks, result assertions, duplicate-target rejection and
  rejection of a malformed later command before an earlier action can run.
- Per-child capability and unattended whitelist checks, unexpected app switches, new user turns,
  overall deadlines, direct coroutine cancellation and the actual capability bridge cancellation
  handler. Completed actions are not replayed.
- Native updates after 900 ms and 2400 ms, explicit condition waits, unmet wait timeouts, and
  stopping before another write when an action has no observed effect.
- Full 60-row lists in both directions, repeated values, output limits, invalid collection bounds
  and native paragraphs longer than the compact 120-character preview.
- Fresh OCR target taps, retired text ids, a changed canvas before tapping, a canvas changed while
  recognition is running, and continuously moving content that must not receive text coordinates.
- Reading and collecting two pages of Android Settings; native Xiaomi Calculator buttons and a
  verified arithmetic result; single-tool versus compound-tool dispatch measurements.

The fixture is a real, non-exported debug Activity with Android widgets and a custom-drawn canvas.
The Settings and Calculator tests operate the installed system apps. The original input method
and accessibility configuration were verified restored after testing.

## Measurements

These are local native measurements, excluding LLM inference and network latency. Three runs
are enough to detect large regressions, not to establish a statistically significant speedup.

| Scenario | Observed result |
| --- | --- |
| Four individual native taps | 2230 / 2125 / 2202 ms; median 2202 ms; four caller tool invocations |
| One compound call containing four taps | 2070 / 2169 / 2175 ms; median 2169 ms; one caller tool invocation |
| Forward collection | 60 rows, 7 pages, 7 scroll attempts, 6468 ms |
| Backward collection | Same 60 rows in reading order, 7 pages, 7 scroll attempts, 6537 ms |
| 900 ms delayed native update | Condition observed and verified in 1540 ms |
| Continuously moving canvas | Stopped recognition in 3438 ms; zero text coordinates issued |

The four-tap native execution times are comparable. The compound call reduces caller tool
invocations from four to one (75%), while retaining Android-side checks and fresh observations.
The collection's last scroll attempt detects the end; both directions returned `scroll_no_effect`
with `truncated: false`, and the entire output matched the known 60-row source including repeats.

## Scope and limits

The tests verify one physical Redmi on the main display. Other Android versions/OEMs, Shizuku
virtual-display operation and live model-driven conversations were not part of this device run.
No model credentials were copied and no end-to-end LLM speedup is claimed. Native settling
cannot predict a future network response; explicit `wait`/`assert` remains necessary. OCR uses
sampled visual comparisons, and ordered text overlap does not provide database row identity.

See [usage and test commands](PHONE-AUTOMATION.md) to reproduce the suite.
