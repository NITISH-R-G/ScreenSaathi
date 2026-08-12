# Architectural decisions

Each entry: what was chosen, and the measured reason — several of these look
like bugs or overengineering until you know what they fixed. Read this before
"cleaning up" anything below.

## Perception

**`flagIncludeNotImportantViews` is set on the accessibility service.**
Without it, whole app trees came back with `elements=0` on a real app (Swiggy)
despite a valid root — measured via `uiautomator`, which sets this flag and
saw 304 nodes where we saw zero.

**Text extraction falls back `text → contentDescription → hintText`.**
An empty field (e.g. "Where to?") is labelled only by its hint. Without the
fallback, exactly the element users most often ask about was invisible to the
resolver.

**`MAX_ELEMENTS` is 600, not the original 120.** Measured: a real screen
(Swiggy home) has 304 nodes with the target at index ~280 — the original cap
silently truncated before reaching it.

**`resolveRoot()` falls back through the window list.** `rootInActiveWindow`
returns null whenever the focused window isn't the visible one — routine
during a transition, a dialog, or our own overlay taking focus. Falling back
to the window list (application windows preferred, topmost layer first) means
`snapshot()` still returns real content instead of `EMPTY` in exactly the
window where a highlight needs to appear.

## Target resolution

**Ranked scoring, never `contains`.** A plain substring match let "call" match
inside "Recall" — a real observed failure. `TargetResolver` scores
exact-text > normalized > word-boundary > id-contains, and reports a
near-tie (`AMBIGUITY_MARGIN`) as `Ambiguous` rather than picking one.

**No app-specific branches, anywhere in the resolver.** Uber's `Where to?`
and PhonePe's `Electricity Bill` resolve through identical code. The only
per-app artifact in the repo is `task/RideApps.kt`, a label→package map used
solely to make ride-app names speakable in three languages — it is not a gate
on what can be resolved.

## Session / highlight lifecycle

**Every user action opens a numbered turn (`SessionController.turnId`).**
Background work checks `isCurrent(turn)` before rendering. Without this, a
mic tap during the ~1.5s "Thinking…" window would start a fresh recording,
then the *older* in-flight plan would land and repaint the pill mid-speech.

**Screen-change invalidation clears the highlight instantly, gated
separately from re-resolution.** The first version gated *both* the clear and
the re-resolve on the new screen settling, and measured ~1.5s of a stale ring
sitting on a screen the user had already left. An unsettled read is still
real evidence of change (element count already diverges before the "settled"
flag flips), so the clear compares signatures on whatever snapshot is
available; only the *re-resolve* waits for settle, so it isn't reading a
half-drawn tree.

**Invalidation uses `TYPE_WINDOW_STATE_CHANGED`, not `TYPE_VIEW_CLICKED`.**
Measured on Uber (a Compose-heavy app): a tap that visibly changes the screen
can emit **zero** `TYPE_VIEW_CLICKED` events, on both a clickable button and a
bare text row. Window-state change is the one signal a real transition is
guaranteed to produce.

**`ScreenReaderService` ignores `TYPE_VIEW_CLICKED` events whose source
package is our own.** Our overlay pill is a real Android View, so tapping the
mic *also* fires a genuine system-wide click event. Routing that through the
same handler as a third-party-app tap caused `abandonRecording()` to kill a
recording ~237ms after it started, measured via `AudioService`'s own
recording-app log. Self-originated clicks are now filtered at the source.

## Overlay window

**Fixed 320dp width, `TOP|START` with explicit x/y — not `WRAP_CONTENT` with
gravity.** `WRAP_CONTENT` made the WindowManager frame a function of the
instruction text; a longer sentence (or the same sentence in Tamil) silently
moved the window's left edge, so the mic control drifted out from under the
finger that had just been placed on it. Gravity-relative coordinates also
make dragging ambiguous — the same `p.x` means a different screen position
depending on which gravity bits are set.

**Overlay window creation is idempotent via a companion-object flag, not an
instance field.** A fresh `Service` object has fresh instance fields
regardless, so an instance flag can't catch two `Service` instances racing on
`startForegroundService()`. The companion-object flag is shared across any
instance in the process and resets only in `onDestroy()`.

**IME (keyboard) detection reads the accessibility window list, not
`WindowInsets`.** The overlay is `FLAG_NOT_FOCUSABLE`, so the keyboard attaches
to the *app's* window; our own window's `rootWindowInsets.ime()` reports "no
keyboard" even while one covers half the screen. `ScreenReaderService`, which
already sees every window on the display via the bound accessibility service,
is the only vantage point that can answer this correctly for a non-focusable
overlay.

**Drag uses `ViewConfiguration.scaledTouchSlop`, checked before treating a
touch as a tap.** Without it, any attempt to move the assistant also opened
the microphone.

## Audio

**RMS for the waveform is computed inside `WavRecorder`'s existing capture
loop, not a second `AudioRecord`.** Two recorders competing for one
microphone is either a hard failure or a silent capture on most OEMs. The
buffer is already in hand in the write loop; there's no reason to open a
second stream to look at it.

**Single-tap capture with a `MAX_UTTERANCE_MS` ceiling (7s), not a pure
toggle.** A pure toggle left turns that the user forgot to close running
forever, or — worse — a double-tap producing a sub-`MIN_SPEECH_MS` clip that
never reached STT at all.

## Deliberately deferred, not forgotten

- **No AI-provider abstraction.** `SarvamPlanner` is called directly rather
  than through an interface. The right shape is something like
  `ScreenSnapshot → ScreenUnderstanding → AgentDecision → SafetyCheck →
  Action`, but introducing it now, under a demo deadline, risked destabilizing
  the one voice pipeline that is actually verified on hardware. Worth doing
  before a second provider is added.
- **No MediaProjection/vision fallback.** Perception is accessibility-only.
  Some apps expose too little to accessibility to be guided; the resolver
  says so rather than guessing from a screenshot. Adding a vision fallback is
  real, scoped work — not something to bolt on casually.
