# Contributing to ScreenSaathi

This is a hackathon-born Android project with a small, explicit architecture
freeze in place — read this before opening a PR, it will save you a
round-trip.

## Before you write code

The five core pieces (overlay pill, `ScreenReaderService`, Saaras STT,
Bulbul TTS, Sarvam-105B planner) and the contracts in `contracts/*.schema.json`
are **frozen**. That means:

- No sixth subsystem. No new top-level component.
- No field is ever removed from a frozen contract — only optional fields may
  be added, and only when the app code and the schema are updated together.
- If your change would replace one of the five pieces, open an issue first and
  explain why the current one can't do the job. Don't just send the PR.

Bugs, prompt/planner improvements, UX polish, reliability, and performance work
are always welcome without asking first — see `docs/PARKING_LOT.md` for what's
already deliberately deferred.

## Good first contribution

Pick an area below — each maps to real, non-frozen surface area. See
[`ROADMAP.md`](ROADMAP.md) for the current planned/in-progress list this
feeds from, and `docs/DECISIONS.md` before touching anything it calls out as
measured/dangerous.

- **AI / agent** — planner prompt tuning (`sarvam/SarvamPlanner.kt`,
  `planner_v1.md`), extending `StepEngine`'s deterministic fallback for a new
  task, or adding a language column to `Phrases.kt` / `PillLabels.kt` (seven
  of ten Sarvam languages still fall back to English UI chrome — see
  `ROADMAP.md`).
- **Android** — overlay/UX polish in `OverlayService.kt` and
  `overlay/HighlightView.kt`, or a per-language TTS speaker instead of the
  single `anand` voice used everywhere today.
- **Vision / perception** — improving `TargetResolver`'s ranked-matching
  scoring, or a fallback matching strategy for third-party button text
  (`task/RideApps.kt` currently hardcodes Uber/Ola/Rapido's English copy with
  no fallback — see `ROADMAP.md`).
- **UX** — barge-in (interrupting the assistant mid-speech), or debug-panel
  improvements (long-press the pill).
- **Safety** — anything in `SafetyGuard`, or hardening `TargetResolver`'s
  refuse-on-ambiguous behavior so it degrades safely on apps that expose very
  little to accessibility.
- **Developer experience** — new tasks under `app/src/main/assets/tasks/`
  (pure JSON, no code — see the README's Contracts section), or expanding
  test coverage in areas `docs/DECISIONS.md` flags as measured-not-obvious.

Bugs, prompt/planner improvements, UX polish, reliability, and performance
work never need a pre-approval issue — open the PR directly.

## Setup

```bash
git clone https://github.com/NITISH-R-G/ScreenSaathi.git
cd ScreenSaathi
```

Create `local.properties` (gitignored, never commit it):

```properties
sdk.dir=/path/to/Android/Sdk
sarvam.api.key=sk_your_key_here
```

The app builds and runs with **no key** — every network call falls back to
the deterministic `StepEngine`. You do not need a Sarvam key to work on the
overlay, accessibility reader, or task DSL.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Workflow

- Small branches, small PRs. One concern per PR.
- Open a **draft PR** until CI is green and you've reviewed your own diff.
- CI (`.github/workflows/android.yml`) must pass: build, unit tests, lint,
  and the secret-scan step. It runs with no Sarvam key on purpose — if your
  change breaks the keyless fallback path, CI is supposed to catch it.
- Never commit `local.properties`, a real API key, or a recorded `.wav` clip
  containing someone's voice.
- Squash tiny fixup commits before opening the PR. Keep history readable.

## Reporting a bug

Use the **Bug report** issue template. Include the Android version, whether a
Sarvam key was configured, and — if it's a voice-loop bug — the debug panel
output (long-press the pill to reveal it).

## Security issues

Do not open a public issue for a security or privacy concern. See
`SECURITY.md`.
