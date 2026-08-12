# AGENTS.md

Canonical entry point for any AI coding agent (Claude, Codex, Copilot, etc.)
picking up ScreenSaathi. Read this first — it's short by design. Everything
substantial lives in `docs/`, linked below; don't duplicate it here.

## What this project is

An Android accessibility overlay that listens (Sarvam STT), reads the live
screen through `AccessibilityService`, resolves the actual UI element the user
needs, draws a ring around it, and waits for the user to tap it themselves. It
does not act on the user's behalf — it points.

## Before you touch anything

```bash
cd src/ScreenSaathi
cp local.properties.example local.properties   # set sdk.dir; sarvam.api.key optional
./gradlew testDebugUnitTest                     # baseline: must be green before AND after your change
./gradlew assembleDebug
```

If a physical Android device is attached: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
**Nothing meaningful can be verified on an emulator** — accessibility, overlay
windows and the microphone don't behave the same way. If you don't have a
device, say so explicitly rather than claiming something works.

## Read these, in order

1. **[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)** — the subsystems, the data
   flow, which file owns what.
2. **[`docs/DECISIONS.md`](docs/DECISIONS.md)** — *why* things are the way they
   are. Several look like bugs until you read the measured reason behind them.
   Read this before "fixing" anything that looks odd.
3. **[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)** — build/test/debug commands,
   device requirements, dangerous areas.
4. **[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)** — device state that
   produces misleading symptoms (force-stop, stale accessibility binding,
   coordinate drift).
5. **[`ROADMAP.md`](../../ROADMAP.md)** (repo root) — what's shipped, in
   progress, and planned. Check it before proposing new work so you don't
   duplicate something already tracked or already deliberately deferred.

## The rule that matters most

**Do not touch these without a measured reason, cited from the device, not a
screenshot guess:**

- `screen/TargetResolver.kt` — ranked matching; refuses ambiguity on purpose.
- `ScreenReaderService.kt` — perception. The flags, the hint-text fallback,
  the `MAX_ELEMENTS` cap and the root-window fallback each fixed a real,
  measured empty-tree bug on a real app. See `docs/DECISIONS.md`.
- `overlay/HighlightView.kt` — geometry and the fly-home vs. instant-clear
  animation split.
- `session/SessionController.kt` — turn/generation invalidation. This is what
  stops stale async work from rendering over a screen the user already left.
- `session/SafetyGuard.kt` — launch authorization and ungrounded-plan
  refusal. Do not weaken this to make a demo path easier.

If you're changing one of these, reproduce the failure on a real device
first, then fix the smallest thing that explains it, then reproduce the fix
on the same device. `docs/DEVELOPMENT.md` has the exact `adb` commands.

## Verify before you claim done

1. `./gradlew testDebugUnitTest` — must stay green.
2. Fresh clone build: clone into an empty directory, follow the README with no
   local state carried over, confirm `assembleDebug` succeeds.
3. If you touched anything device-facing (overlay, accessibility, voice,
   highlight), install on a real device and re-run the relevant scenario in
   `docs/DEMO.md`. State exactly what you tested and what you didn't — "should
   work" is not evidence.

## Known gaps, intentionally not addressed here

- No AI-provider abstraction yet — Sarvam is called directly from
  `sarvam/SarvamPlanner.kt`, not behind an interface. Worth doing before adding
  a second provider; not done because it risked the working demo pipeline
  under time pressure. See `docs/DECISIONS.md`.
- No CI lint/static-analysis step, only build + unit tests + secret scan.
- Package layout (`overlay/`, `screen/`, `session/`, `sarvam/`, `device/`,
  `launcher/`, `task/`) is reasonable but not renamed to match any particular
  convention — don't restructure it as a drive-by change.
