# ScreenSaathi

A screen-aware voice copilot for Android. A floating Dynamic-Island-style pill
listens, understands what the user is trying to do, reads the screen through
accessibility, draws a highlight on the real element, and speaks the next
instruction aloud.

It is **not** a chatbot and not a generic voice assistant. It is a guided screen
companion: it points at the thing you should touch next, and tells you why.

## The five pieces (and only five)

| Piece | File | Job |
| --- | --- | --- |
| Overlay pill | `OverlayService.kt`, `overlay/HighlightView.kt` | Renders an `OverlayCommand`. Never reasons. |
| Screen reader | `ScreenReaderService.kt`, `screen/ScreenSnapshot.kt` | Read-only accessibility snapshot. No gestures. |
| Saaras STT | `sarvam/SarvamStt.kt`, `sarvam/WavRecorder.kt` | 16 kHz mono WAV → transcript + language. |
| Sarvam-30B planner | `sarvam/SarvamPlanner.kt` | Forced tool call → the frozen planner contract. |
| Bulbul TTS | `sarvam/SarvamTts.kt`, `sarvam/AudioPlayer.kt` | Instruction → warm spoken audio (`anand`). |

`session/SessionController.kt` orchestrates them. `session/StepEngine.kt` is the
deterministic core that completes the task with no network at all.

Architecture is **frozen** after M1. See `PARKING_LOT.md`.

## Build

Create `local.properties` in the project root (it is gitignored):

```properties
sdk.dir=C\:/path/to/Android/Sdk
sarvam.api.key=sk_your_key_here
```

The key is read into `BuildConfig.SARVAM_API_KEY` at build time, so no key is
ever hardcoded in a source file. **Without a key the app still runs** — every
network step falls back to the deterministic `StepEngine`, which is what the
offline demo path uses.

```bash
./gradlew assembleDebug
```

```bash
./gradlew test
```

## Contracts (frozen at M1)

`contracts/` holds the four JSON Schemas that everything else is written
against: `planner`, `task`, `accessibility`, `overlay`. No field is ever removed
after M1 — only optional fields may be added.

Tasks are pure data: `app/src/main/assets/tasks/*.json`. Adding a task is
dropping a file there, not writing code.

## Running the demo

1. Launch the app, grant **display over other apps**, **microphone**, and enable
   the **ScreenSaathi screen reader** in Accessibility settings.
2. Tap **Start** — the pill appears at the bottom edge.
3. Open the demo task screen.
4. Tap the pill to expand, tap the mic, say what you want to do, tap the mic
   again. The ring lands on the field; the instruction is spoken.

Long-press the pill to toggle the debug panel (transcript, intent, step, target,
latency, confidence). Keep it hidden during a real demo.

> **Demo-day landmine:** `adb install` or force-stop silently unbinds the
> accessibility service. The app keeps working but the highlight stops resolving.
> After any reinstall, toggle the screen reader off and on. See `PARKING_LOT.md`.
