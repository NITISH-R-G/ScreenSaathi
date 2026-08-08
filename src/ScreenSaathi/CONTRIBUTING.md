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
