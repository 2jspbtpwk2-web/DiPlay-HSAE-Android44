# Actual activity UI regression

Compile `UiSmokeInstrumentation.java` against the API 18 Android jar with Java 7 bytecode and UTF-8 source encoding. Package it with this manifest as a separate APK, then sign it with the installed DiPlay APK's certificate. No product code or test hooks are needed.

The product must already be installed. Run on the emulator after stopping any previous wired connection service. This test refuses to run while `LegacyWiredService` is active, and never starts a USB connection or changes Android permissions.

```text
adb shell am instrument -w -e cycles 30 local.airuize.diplaylegacy.uismoke/.UiSmokeInstrumentation
```

The default is 30 cycles; the accepted range is 30–200. Each cycle opens/cancels settings, scrolls the dashboard, exercises the real menu and back behavior with a synthetic fullscreen arrangement, then performs three color selections/saves. Every five cycles it backgrounds/resumes and requests the real platform `Activity.recreate()` path. An `ActivityMonitor` captures the replacement instance. The old instance must reach both the framework and product destroyed states, and its touch worker and artwork executor must exit. No lifecycle callback is invoked manually.

Two additional system-recreation checks cover an open settings dialog and a USB-attach delayed connection callback. The old dialog registry must be empty after actual destruction. The USB intent is consumed and system recreation is requested in one main-thread turn; the replacement retains the MAIN intent, and the wired service must remain absent after a further 900 ms.

Clicks locate visible `TextView` text in the target process's window trees. Choice dialogs use their real `AdapterView.performItemClick`, and the dashboard uses `performClick`. No screen coordinates are used. This verifies application callbacks and lifecycle stability; it does not reproduce a physical touchscreen gesture, a connected iPhone, or vehicle hardware performance.

## Required result checks

- Require the exact final `PASS UiSmokeInstrumentation` result, at least 30 cycles, and successful preference/service restoration.
- Any `FAIL`, target exception, process crash/restart, instrumentation abort, or missing final PASS fails the run. A successful restoration command does not change a failed UI result into a pass.
- `mainBodyUs` reports time inside each main-thread operation, including view lookup. `dispatchWaitUs` reports the wait before that operation started. Neither measures display FPS or CarPlay frame rate.
- Production `interactionDiagnostic` is captured every five cycles. It includes the low-frequency UI scheduling and touch queue counters.

## Lifecycle trigger and emulator observation

In earlier runs on this KitKat emulator, ordinary `finish()` after background/resume produced an Activity that was finishing, paused, not stopped, unfocused, and not destroyed. The target reference matched the sole `ActivityThread.mActivities` record; its main thread still answered diagnostic calls. This observation does not establish an application lock or a specific Activity Manager bug. Separate real diagnostic runs using CLEAR_TASK did reach destruction.

The resource-lifetime checks therefore use system recreation, which explicitly drives a real old-instance destroy/new-instance create transition without waiting for Launcher visibility. A missing real destruction or replacement still fails the test. Lifecycle diagnostics retain object/token identity and framework records to expose reference mistakes.

Final cleanup requests ordinary `finish()` and records the resulting lifecycle state. It does not require immediate destruction, and this cleanup step is never counted as a resource-destruction pass. The destruction checks counted by this suite are exclusively the verified old instances from system recreation.

## Preference restoration and abnormal termination

Only the five preferences written by the real settings Save button are backed up: display mode, car color, steering voice, microphone, and vehicle GPS. Their exact original presence, values, and types are restored in `finally`; the observed original steering service running state is also restored. Other preferences remain untouched. No clear-data operation is used.

Before temporarily changing preferences, the test synchronously saves this small backup in the target app cache. Normal cleanup removes it. An operating-system kill cannot execute Java `finally`, so always run this recovery command after an interrupted/failed instrumentation invocation, and preferably as a postflight check after every invocation:

```text
adb shell am instrument -w -e restoreOnly true local.airuize.diplaylegacy.uismoke/.UiSmokeInstrumentation
```

It restores a pending backup without launching activities. With no pending backup it changes no preferences. Require `PASS UiSmokeRestoreOnly`. The test APK can then be uninstalled. The product APK and its user data remain installed.
