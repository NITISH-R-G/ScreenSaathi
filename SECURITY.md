# Security Policy

## Reporting a vulnerability

Please do **not** open a public GitHub issue for a security or privacy
finding. Instead:

- Open a private security advisory via GitHub: **Security → Advisories →
  Report a vulnerability** on this repository, or
- Contact the repository owner directly through their GitHub profile.

Include what you found, how to reproduce it, and its impact. This is a small,
actively-developed project with one maintainer — expect an initial response
within a few days, not an SLA.

## What this app can see, and what leaves the device

ScreenSaathi requests `BIND_ACCESSIBILITY_SERVICE`, which is the most
sensitive permission Android exposes — it lets the app read the on-screen
content of whatever app is in the foreground. This is documented plainly, not
buried:

- `ScreenReaderService` reads a flattened snapshot of the current screen
  (resource ids, visible text, class names, bounds, editable/clickable flags)
  and skips its own overlay's views. It does **not** take screenshots and does
  **not** read arbitrary background apps — only the foreground window, only
  while the service is enabled.
- That snapshot is sent, as text, to the Sarvam-30B planner API
  (`sarvam/SarvamPlanner.kt`) to decide which step and element to point at.
  Recorded audio is sent to Saaras STT; the spoken instruction is sent to
  Bulbul TTS. All three are outbound calls to `api.sarvam.ai` over HTTPS.
- No screen content, transcript, or audio is persisted to disk beyond the
  input `.wav` (deleted immediately after each STT call — see
  `SessionController.process()`) and no analytics, telemetry, or usage data is
  collected by this app at all — see `docs/DATA_HANDLING.md` for the full
  accounting.

This is disclosure of current behavior, not a legal privacy policy. Before any
production or public release, have that language reviewed by someone
qualified to write one — this document is not a substitute.

## Secrets

The Sarvam API key lives only in a gitignored `local.properties` and reaches
the app through `BuildConfig` at build time. CI runs `gitleaks` on every push
and PR (see `.github/workflows/android.yml`) to catch a key landing in the
tree by accident. If you find a real key committed anywhere in this repo's
history, treat it as compromised and report it privately — rotating a key
after the fact does not remove it from git history.
