# Roadmap

What's actually shipped, what's actively being worked, and what's deliberately
deferred. Sourced from `src/ScreenSaathi/docs/PARKING_LOT.md` and `src/ScreenSaathi/docs/DECISIONS.md` — no
item here is speculative marketing; each is either verified working or
explicitly logged as a known gap.

## Shipped

- Voice → intent → live accessibility snapshot → ranked target resolution →
  visual ring → user taps it themselves. Verified end-to-end on a real
  taxi-booking flow (Uber) and a bill-payment flow (PhonePe).
- Deterministic `StepEngine` fallback — completes the guided task with **no
  network at all** if Saaras STT, the planner, or Bulbul TTS fails or times
  out.
- Three-language support: Hindi, Tamil, English — detected from speech,
  answered back in the same language. Saaras detects and Bulbul speaks all
  ten Sarvam-supported languages; only Hindi/Tamil/English currently have
  full UI chrome translated (see Planned).
- Movable, keyboard-aware overlay: fixed 320dp window, drag with touch-slop
  gating, repositions itself clear of the on-screen keyboard by reading the
  accessibility window list (the overlay's own `WindowInsets` can't see the
  IME — see `src/ScreenSaathi/docs/DECISIONS.md`).
- Real-time microphone waveform in the listening state, computed from actual
  RMS inside the existing recording loop — not a decorative animation.
- App-agnostic target resolution: `TargetResolver` has no per-app branches;
  the same ranked-scoring code resolves Uber's `Where to?` and PhonePe's
  `Electricity Bill`. Refuses to guess on an ambiguous or absent match.
- Frozen five-piece architecture + JSON Schema contracts (`src/ScreenSaathi/contracts/`) from
  the M1 milestone — no field ever removed, only optional additions.
- CI: build, unit tests, lint, and a secret-scanning guard on every push.
- GitHub Release (`v0.1.0`) with a signed-off debug APK, SHA256 checksum, and
  install instructions.

## In progress

- **Hindi "skip ahead" phrasing** — fixed once (`src/ScreenSaathi/docs/PARKING_LOT.md`,
  2026-07-31) after the forced `sarvam-30b` → `sarvam-105b` model swap;
  behavior around correction/"go back" utterances on the new model needs a
  second verification pass before it's fully trusted in a live demo.
- **Planner latency** — currently 1261–3300 ms against a 700 ms target after
  the `sarvam-105b` swap (the smaller `sarvam-30b` model was deprecated
  upstream). The visual highlight no longer waits on this (TTS and bounds
  resolution run in parallel), but the number itself is still over budget.
- **Third-party app coverage beyond Uber/PhonePe** — the resolver itself is
  app-agnostic and spot-checked against a few other apps (Settings, the
  ScreenSaathi launcher), but broad third-party coverage is not yet
  systematically verified.

## Planned (not started)

- Remaining seven Sarvam-supported languages (Bengali, Gujarati, Kannada,
  Malayalam, Marathi, Punjabi, Telugu) get full UI chrome ("Listening…",
  button labels) — currently only the spoken planner instructions localize;
  the pill's own text falls back to English. Mechanically an addition to
  `Phrases.kt` and `PillLabels.kt` in the Android project's `session/` and
  `overlay/` packages, not a redesign.
- Per-language TTS voice — every language currently speaks with the same
  Bulbul speaker (`anand`).
- Barge-in: interrupting the assistant by speaking over it. Currently the
  user must tap Stop; this needs continuous capture, which is a real
  architecture change, not a quick patch.
- A resilient matching strategy for third-party app button text
  (`book_taxi.json` currently matches Uber/Ola/Rapido by hardcoded English
  visible text, with no fallback if any of them change their copy) — tracked
  as an open design question (semantic matching vs. OCR vs. per-app
  adapters), not a scoped fix yet.
- An AI-provider abstraction (`ScreenSnapshot → ScreenUnderstanding →
  AgentDecision → SafetyCheck → Action`) so a second model/provider could sit
  behind `SarvamPlanner` — deliberately not built yet; see
  `src/ScreenSaathi/docs/DECISIONS.md` for why introducing it under a demo deadline was judged
  too risky to the one verified pipeline.
- A MediaProjection/vision fallback for apps that expose too little to the
  Accessibility API — currently the resolver just reports `NotFound` rather
  than guessing from a screenshot, which is the intended safe behavior, not
  a placeholder for the eventual fallback.

## Explicitly out of scope

Real third-party app automation (auto-filling fields, gesture injection,
clipboard paste, readback verification), a general screenshot/vision
perception path as the *primary* mode, a GPT-Realtime or other alternate
realtime architecture replacing the current Sarvam pipeline, proactive
background watching, telemetry, multi-agent loops, and any account/auth/
settings/analytics surface. See `src/ScreenSaathi/docs/PARKING_LOT.md` for the full list and
reasoning.
