# Development

## Requirements

JDK 21 · Android SDK (compileSdk 36.1, build-tools 36.1.0) · minSdk 26. Gradle
itself is not required — the wrapper is committed.

## Setup

```bash
cd src/ScreenSaathi
cp local.properties.example local.properties
# edit local.properties: set sdk.dir; sarvam.api.key is optional (see below)
```

`sarvam.api.key` (from [dashboard.sarvam.ai](https://dashboard.sarvam.ai)) is
only needed for live voice. Without it, the project still builds, installs
and runs — `StepEngine` completes tasks deterministically, no network
required.

## Build

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Test

```bash
./gradlew testDebugUnitTest       # JVM only, no device
```

3 tests are `replay_only` fixtures that skip without a live Sarvam key — this
is expected, not a failure.

## Install and run on a device

**A physical Android device is required.** Accessibility services, overlay
windows and the microphone don't behave meaningfully on an emulator.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, on the device: open ScreenSaathi → grant microphone → grant "display
over other apps" → enable the Accessibility Service → tap **Start assistant**.

## Debugging on-device

```bash
# Everything from our own process
adb logcat -d --pid=$(adb shell pidof com.screensaathi | tr -d '\r')

# Target resolution decisions
adb logcat -s TargetResolver:*

# Session/turn/highlight state
adb logcat -s SessionController:*

# Confirm exactly 2 overlay windows (highlight layer + pill) — more means a
# duplicate registration, fewer means the service isn't running
adb shell dumpsys window windows | grep -c "Window{.*com.screensaathi}"

# Confirm the accessibility service is bound and hasn't crashed
adb shell dumpsys accessibility | grep -E "Bound services|Crashed services"

# Real window position/frame (never guess from a screenshot — see
# docs/TROUBLESHOOTING.md for why)
adb shell dumpsys window windows | grep -A20 "Window{.*com.screensaathi}:" | grep -E "Window #|frame=|mAttrs="

# Manual highlight trigger (exercises the same resolver/overlay the voice
# path uses; only STT is skipped)
adb shell am broadcast -n com.screensaathi/.HighlightReceiver \
  -a com.screensaathi.HIGHLIGHT --es query "Where to"

# Dump what the resolver currently sees on screen
adb shell am broadcast -n com.screensaathi/.HighlightReceiver \
  -a com.screensaathi.DUMP_SCREEN
```

## Dangerous areas — measure before changing

See `docs/DECISIONS.md` for the full reasoning. Short version: don't touch
`TargetResolver`, `ScreenReaderService`, `SessionController`'s turn/generation
logic, `HighlightView`'s clear-vs-animate split, or `SafetyGuard` without
reproducing a real, measured failure first — several of the choices in those
files fixed specific bugs that don't announce themselves in a screenshot.

**Never `am force-stop com.screensaathi` during testing.** It disables the
Accessibility Service, which silently invalidates everything downstream —
this produced multiple false "bug" reports during development that were
actually test-harness artifacts. Only force-stop *other* apps (Uber, PhonePe)
to reset their state.

## Release process

1. Bump version if applicable, run `testDebugUnitTest` clean.
2. `./gradlew assembleDebug`, note the commit SHA the build came from.
3. `sha256sum app/build/outputs/apk/debug/app-debug.apk`.
4. Install the exact built APK on a device and re-run the scenarios in
   `docs/DEMO.md`. Don't ship an APK you haven't installed.
5. `gh release create vX.Y.Z --target <sha> --title "..." --notes-file ...`,
   attach the APK and its checksum.
6. Confirm from a **fresh clone** (not the working directory) that the same
   commit builds and the release asset downloads and installs correctly.
