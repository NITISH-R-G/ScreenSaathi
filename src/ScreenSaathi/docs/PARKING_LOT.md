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

Measured via `scripts/smoke_sarvam.ps1`, `scripts/smoke_languages.ps1`, and
`scripts/planner_case.ps1` on 2026-07-26 and re-measured 2026-07-31 after the
forced model swap below. Venue network not yet tested.

| Layer                  | Target      | Measured (07-26) | Measured (07-31) | Status |
| ---------------------- | ----------- | ----------------- | ----------------- | ------ |
| Saaras STT             | < 800 ms    | 666–729 ms         | not re-measured    | OK     |
| Planner                | < 700 ms    | 867–1481 ms        | **1261–3300 ms**   | OVER   |
| Overlay update         | < 16 ms     | not instrumented   | not instrumented   | —      |
| Bulbul TTS first audio | < 900 ms    | 909–1462 ms        | not re-measured    | OVER   |
| End-to-end response    | < 2.5 s     | not instrumented   | not instrumented   | —      |

**Neither over-budget layer blocks the visual any more.** TTS now runs on its
own thread, started in parallel with bounds resolution rather than after it, so
the cursor and ring land while Bulbul is still synthesising.

**2026-07-31: forced model swap, `sarvam-30b` → `sarvam-105b`.** Sarvam
deprecated `sarvam-30b` sometime between 07-26 and 07-31 — a live call now
returns a hard 400 (`"Model 'sarvam-30b' has been deprecated... use
sarvam-105b"`). This is not optional or scheduled work: the planner was
silently dead (falling back to the deterministic engine on every real call)
until this was caught and fixed. `Sarvam.PLANNER_MODEL` now points at
`sarvam-105b`, re-verified live via `scripts/planner_case.ps1` against the
same six-case regression set used on 07-26.

The swap is a net negative on latency: 105B is a bigger, slower model, and
measured planner latency roughly doubled (1261–3300 ms vs 867–1481 ms). It is
still capped at the same hard 5 s call timeout (`Sarvam.plannerHttp`) — beyond
that, falling back to the free, instant `StepEngine` is still strictly better
than waiting. Reducing planner latency is now a harder problem than trimming
the prompt (M4 item, unchanged) — it may require asking Sarvam for a smaller
model tier once one exists, or moving skip-ahead/correction handling into
deterministic keyword matching so the LLM is only consulted when genuinely
ambiguous.

One behavioral difference worth watching, not yet resolved: on `sarvam-105b`,
"अरे नहीं, गलती हो गई" (correction, said on the first step) now returns
`step: account` instead of the previous `step: amount` — moving the user
forward on a "we go back" utterance where there is no earlier step to return
to. Not confirmed as objectively wrong (there's no clean target for "go back"
from step one), but it's a change in behavior from the same prompt against a
different model, worth a second look before relying on the correction path
in a demo.

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

- ~~Planner ignores "skip ahead" in Hindi.~~ **Fixed 2026-07-31**: the prompt
  now enumerates skip-ahead phrasing across languages explicitly
  (`planner_v1.md`); re-verified live, "सीधे submit पर ले चलो" now correctly
  returns `step: submit`. See the latency section above for what else changed
  in the same pass (the forced `sarvam-105b` swap).
- Phrases are authored in English, Hindi, **and Tamil** (`session/Phrases.kt`,
  `overlay/PillLabels.kt`) as of the taxi-flow work. Bulbul speaks ten
  languages and Saaras detects all ten; the remaining seven (Bengali,
  Gujarati, Kannada, Malayalam, Marathi, Punjabi, Telugu) still get planner
  instructions in the right language but English chrome ("Listening…").
  → **UX improvement**; adding a language is adding a column to `Phrases` and
  `PillLabels`.
- TTS speaker is `anand` for every language. Verified to work in all ten, but a
  per-language voice would sound better. → **UX improvement**, PARKED.
- No barge-in: speaking over the assistant does not interrupt it, the user has
  to tap Stop. → **UX improvement**, PARKED (needs continuous capture).
- `book_taxi.json`'s `pick_ride`/`destination` steps match Uber/Ola/Rapido by
  hardcoded English button text (`text_any`), with no fallback if any of the
  three apps change their copy. → tracked as a GitHub issue, not fixed here —
  it needs a real design decision (semantic matching? OCR? per-app adapters?),
  not a quick patch. See issues labeled `audit-followup`.

## Explicitly out of scope (from the reference app, deliberately dropped)

- Real third-party app automation (gestures, clipboard-paste text entry, readback verification)
- Screenshot / vision fallback path
- GPT Realtime, proactive watcher, routine miner, telemetry, web search, contacts
- Multi-agent loops
- Database, auth, onboarding, settings screens, analytics
