# Demo flows

All three assume ScreenSaathi is installed, permissions are granted, and the
Accessibility Service is enabled (see `README.md` → Installation).

## 1 · Taxi booking — verified on device

> **"Help me book a taxi."**

```
voice → intent → Uber discovered (real PackageManager lookup, not a
hardcoded package) → Uber launched → live accessibility snapshot taken →
"Where to?" resolved (ranked match, not a coordinate) → ring drawn around
the real element → user taps it → screen-change detected → old ring
cleared → new screen read
```

**Fallback if network/STT fails:** the deterministic `StepEngine` completes
the same guided flow without any network call — same overlay, same
highlight, no live speech.

## 2 · Bill payment — verified, may require unlocking PhonePe

> **"Help me pay my electricity bill."**

```
voice → intent → PhonePe launched → home screen read (57 elements observed
in testing) → "Electricity Bill" resolved (score 80, normalized match) →
highlighted → user taps → Electricity screen read
```

PhonePe can present its own security lock after the screen has been idle.
**This is not bypassed.** If it appears: unlock PhonePe manually, then
continue from where the flow left off.

## 3 · A different app — architecture claim, partially verified

Nothing in `TargetResolver` or `ScreenReaderService` is Uber- or
PhonePe-specific — the same ranked-matching code runs against whatever the
foreground app publishes to the accessibility tree. This has been spot-checked
against a handful of other installed apps (Settings, the ScreenSaathi
launcher's own app grid) with consistent behaviour.

**What's honestly unverified:** broad coverage across arbitrary third-party
apps. Some apps expose very little to accessibility; where that's true,
`TargetResolver` returns `NotFound` and the assistant says it can't see the
target, rather than guessing. That refusal is itself the intended, safe
behaviour — not a bug to route around.

## If something doesn't work during a live demo

- **Stuck on "Thinking…"** — almost certainly the Accessibility Service got
  disabled (reinstall, or a stray force-stop). See `docs/TROUBLESHOOTING.md`.
- **No highlight appears** — the target genuinely wasn't found; try rephrasing,
  or check `adb logcat -s TargetResolver:*` for the `NotFound`/`Ambiguous`
  reason.
- **Assistant missing entirely** — check window count
  (`adb shell dumpsys window windows | grep -c "Window{.*com.screensaathi}"`);
  if 0, tap **Start assistant** again from the app.
