# Impact measurement

ScreenSaathi's stated purpose is helping people who find modern Android
interfaces hard to navigate. This document defines how that claim is
**measured**, not asserted.

**There are no numbers in this document.** Every metric below is defined with
its instrument and its current status. Where a figure is not yet collected it
says so. Filling these in requires real users and real sessions; inventing
them would defeat the purpose of having the framework at all.

## Why a framework instead of a claim

"Helps elderly users navigate apps" is unfalsifiable as written. The
metrics below are chosen so that a bad build makes them worse and a good
build makes them better, and so that a sceptical reader can reproduce them
from `evals/` rather than taking the claim on trust.

## Product metrics

| Metric | Definition | Instrument | Status |
| --- | --- | --- | --- |
| Task completion rate | Guided tasks reaching their final step / tasks started | Session log | **Not yet collected** — needs real users |
| Target resolution accuracy | Correct element resolved / resolution attempts | `evals/` dataset with labelled screens | **Partially instrumented** — harness exists, dataset small |
| Clarification rate | Turns where the assistant asked instead of acting | `CIRCLE_CLARIFY` log line | **Instrumented, unmeasured** |
| Agent intervention rate | Steps where the user needed help vs. proceeded alone | Session log | **Not instrumented** |
| Average user actions per task | Taps required end to end | Session log | **Not instrumented** |
| Time to completion | First request to final step | Session log | **Not instrumented** |

## AI-quality metrics

| Metric | Definition | Why it matters | Status |
| --- | --- | --- | --- |
| Hallucination rate | Answers asserting content not present in tree or pixels | The core risk for users who cannot verify the answer | **Not measured** — no vision provider to measure |
| Accessibility-only resolution rate | Selections resolved with no model call | Directly measures cost, latency and privacy exposure | **Instrumented** via `PerceptionStrategy` |
| Vision fallback rate | Selections requiring pixels | Sizes the spend a vision provider would incur | **Instrumented** via `PerceptionStrategy` |
| Refusal correctness | Correct "I can't see that" vs. wrong refusal | Over-refusing is a real failure too | **Not measured** |

The accessibility-only rate is the metric this architecture is built around:
every point of it is a screenshot **not** sent off-device. See
`PerceptionStrategy`.

## Reliability metrics

| Metric | Instrument | Status |
| --- | --- | --- |
| Crash-free sessions | Play/Crashlytics equivalent | **Not instrumented** — no analytics SDK by design (`docs/DATA_HANDLING.md`) |
| Overlay window leaks | `dumpsys window` count must stay at 2 | **Verified manually** on device |
| Cached crop growth | Files in cacheDir after N selections | **Verified**: 10 selections → 1 file |

Note the tension, stated rather than hidden: ScreenSaathi ships **no analytics
SDK**, so several product metrics above cannot be collected without adding one.
That is a deliberate privacy choice with a real measurement cost. Any future
telemetry must be opt-in and documented in `docs/DATA_HANDLING.md` before it is
built.

## Accessibility-specific objectives

Measurable, and currently unmeasured:

- **Reduced interaction complexity** — fewer taps and less backtracking to
  complete a task with guidance than without. Requires a paired A/B with real
  users.
- **Unfamiliar-interface navigation** — completion rate on an app the
  participant has never used.
- **Multilingual parity** — completion rate in Hindi and Tamil should not be
  materially below English. Currently **untested with live speech in Tamil**
  (see the README's limitations).
- **Failed-interaction reduction** — taps on the wrong control before and
  after guidance.

## How to collect these honestly

1. Extend `evals/` with labelled screens per category (see `evals/README.md`).
2. Run the same dataset across providers to fill the scorecard in
   `docs/AI_PROVIDERS.md`.
3. For anything requiring humans, run a small supervised study and report
   n, the protocol, and the confidence interval — not a bare percentage.
4. Publish the dataset and harness alongside the numbers so they are
   reproducible.

Until step 3 happens, this project should describe its impact as
**intended and architecturally supported, not demonstrated**.
