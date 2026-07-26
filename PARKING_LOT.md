# PARKING_LOT.md

Architecture + Contract freeze is in effect after M1. Any request that arrives
after M1 goes here **unless** it satisfies exactly one of:

- Planner improvement
- Prompt improvement
- UX / animation improvement
- Reliability improvement
- Performance improvement

Everything else waits until after the demo. No new modules. No new contract
fields removed — only optional additions.

## Latency budgets (optimize the offending layer, don't guess)

Measured via `scripts/smoke_sarvam.ps1` on 2026-07-26, venue network not yet tested.

| Layer                  | Target      | Measured        | Status |
| ---------------------- | ----------- | --------------- | ------ |
| Saaras STT             | < 800 ms    | 729 ms          | OK     |
| Planner (Sarvam-30B)   | < 700 ms    | 606 ms          | OK     |
| Overlay update         | < 16 ms     | not instrumented| —      |
| Bulbul TTS first audio | < 900 ms    | 1166–1427 ms    | OVER   |
| End-to-end response    | < 2.5 s     | not instrumented| —      |

**TTS is the one layer over budget.** Not blocking the visual (the highlight
lands before speech starts), so it is an M4 performance item, not an M1 blocker.
Options when we get there: shorter instruction strings, or start TTS
concurrently with bounds resolution instead of after it.

## Demo-day gotchas (learned the hard way on device)

1. **`adb install` / force-stop silently unbinds the accessibility service.**
   The app keeps running and still shows instructions, but the highlight stops
   resolving (`reader: NULL`). After ANY reinstall, toggle the screen reader off
   and on in Settings. This looks like total failure on stage and the cause is
   invisible — check it first.
2. **Long-press the pill** to reveal the debug panel (transcript, intent, step,
   target, latency, confidence). Device logcat is too noisy to be usable; this
   panel is the real triage tool. Keep it hidden during the demo.
3. Screen sleep kills a scripted run — `adb shell svc power stayon usb`.

## Parked items

_(none yet — add as `- [source] idea → which of the 5 buckets, or PARKED`)_

## Explicitly out of scope (from the reference app, deliberately dropped)

- Real third-party app automation (gestures, clipboard-paste text entry, readback verification)
- Screenshot / vision fallback path
- GPT Realtime, proactive watcher, routine miner, telemetry, web search, contacts
- Multi-agent loops
- Database, auth, onboarding, settings screens, analytics
