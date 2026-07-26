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

Measured via `scripts/smoke_sarvam.ps1`, `scripts/smoke_languages.ps1` and
`scripts/smoke_planner_language.ps1` on 2026-07-26. Venue network not yet tested.

| Layer                  | Target      | Measured        | Status |
| ---------------------- | ----------- | --------------- | ------ |
| Saaras STT             | < 800 ms    | 666–729 ms      | OK     |
| Planner (Sarvam-30B)   | < 700 ms    | 867–1481 ms     | OVER   |
| Overlay update         | < 16 ms     | not instrumented| —      |
| Bulbul TTS first audio | < 900 ms    | 909–1462 ms     | OVER   |
| End-to-end response    | < 2.5 s     | not instrumented| —      |

**Neither over-budget layer blocks the visual any more.** TTS now runs on its
own thread, started in parallel with bounds resolution rather than after it, so
the cursor and ring land while Bulbul is still synthesising.

The planner regressed from 606 ms to ~900–1400 ms when the prompt grew to carry
the language contract (713 prompt tokens). It is capped at a hard 5 s call
timeout (`Sarvam.plannerHttp`) because the deterministic step engine answers
instantly and for free — beyond a few seconds, falling back is strictly better
than waiting. Trimming the prompt further is the M4 lever.

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

- Planner ignores "skip ahead" in Hindi. "सीधे submit पर ले चलो" (take me
  straight to submit) returns `step: amount`. English skip-ahead was never
  re-tested after the prompt rewrite. → **Planner/prompt improvement**, worth
  fixing before the demo if a judge is likely to try it.
- Phrases are authored in English and Hindi only. Bulbul speaks ten languages
  and Saaras detects all ten, so a Tamil speaker gets Tamil *planner*
  instructions but English chrome ("Listening…"). → **UX improvement**;
  adding a language is adding a column to `Phrases`.
- TTS speaker is `anand` for every language. Verified to work in all ten, but a
  per-language voice would sound better. → **UX improvement**, PARKED.
- No barge-in: speaking over the assistant does not interrupt it, the user has
  to tap Stop. → **UX improvement**, PARKED (needs continuous capture).

## Explicitly out of scope (from the reference app, deliberately dropped)

- Real third-party app automation (gestures, clipboard-paste text entry, readback verification)
- Screenshot / vision fallback path
- GPT Realtime, proactive watcher, routine miner, telemetry, web search, contacts
- Multi-agent loops
- Database, auth, onboarding, settings screens, analytics
