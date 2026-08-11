# Device awareness and open-ended action safety

Screen awareness answers *"what is visible?"*. Device awareness answers *"what
exists on this phone?"*. ScreenSaathi had the first and not the second, and the
planner was never told which was which — so it answered device questions from
world knowledge and announced *"launching instagram app"* on a phone with no
Instagram.

This document records what was fixed, what was measured, and — at least as
importantly — what is still missing.

## What changed

| Component | Purpose |
|---|---|
| `device/DeviceContext.kt` | Read-only snapshot with an explicit three-state availability model |
| `device/DeviceContextProvider.kt` | Builds it from `PackageManager`, honouring the existing `<queries>` limit |
| `SafetyGuard.validateOpenEndedAction` | Gate for `click` / `type_text` / `launch_app` — screen evidence, payload provenance, irreversible targets |
| `SafetyGuard.validateLaunch` | Evidence check: present, launchable, enabled, unambiguous |
| `SafetyGuard.validateLaunchAuthorization` | **Authorisation** check: did the user *name* this app? |
| `SessionController` | Routes blocked plans to the existing `guide`; handles the launch result instead of discarding it |

### The authorisation rule

One deterministic rule, no classifier, no second model call:

> A launch is authorised only if the user's request **names** an app that device
> evidence verifies.

Matching is against the label device evidence reports, not a keyword list — the
same principle as the `type_text` provenance rule. It separates three intent
classes without ever declaring them:

- *"Open Uber"* → names Uber → **allowed**
- *"Find an app for booking a cab"* → names nothing → **blocked**
- *"Find my downloaded PDF"* → names nothing → **blocked** (opening Files is
  not finding the file)

## Measured results

LIVE, `sarvam-105b`, 15 synthetic device cases (`evals/datasets/device_awareness/device_v1.jsonl`).

| Metric | Before | After |
|---|---:|---:|
| Unauthorised device actions | 3 | **0** |
| Ambiguity handled | 0/1 | **1/1** |
| Content-request unauthorised actions | 1 | **0** |
| Explicit app launches preserved | 2/2 | **2/2** |
| False-existence on authoritative ABSENT | 0/4 | 0/4 |
| False-absence | 0 | 0 |
| Evidence-grounded | 12/15 | **12/15** |
| Settings → CAPABILITY_UNSUPPORTED | 2/2 | 2/2 |

Deterministic suites: `DeviceEvaluatorLogicTest` 23/23 · `DeviceContextTest` 14/14 ·
`OpenEndedGuardTest` 17/17 · `SafetyGuardTest` 8/8 · guided golden 8/8, 0 critical.

### An evaluator that was wrong first

The first device evaluator scored behaviour with substring checks and was wrong
in **both** directions — it called a correctly hedged answer a hallucination, and
called an ambiguous cab request a clean Uber resolution. Those numbers are
superseded. v2 classifies only from structured signals: device state from
`DeviceContext`, existence claims from `action_type`, ambiguity from intent plus
candidate count, execution from the real guard verdict.

## Limitations — read before quoting any number above

1. **Package visibility exposes only the seven configured packages.** The
   manifest `<queries>` allow-list is the entire universe `PackageManager` can
   see. This is not a complete device inventory.
2. **`QUERY_ALL_PACKAGES` was deliberately NOT added.** It is a Play-policy
   restricted permission. Broadening visibility is a product and privacy
   decision, not a code change.
3. **UNKNOWN must never be reported as absent.** Not seeing an app is not
   evidence that it is missing. `humanStatus()` says "I couldn't verify" and
   never "you don't have it".
4. **`DeviceContext` has not been validated on physical hardware.** The
   provider is unit-tested and reasoned about, never run on a real device.
5. **Settings intent resolution is not implemented.** Settings requests are
   scored `CAPABILITY_UNSUPPORTED`, never success.
6. **Full universal search is not implemented**, and cannot be under the current
   visibility constraint.
7. **Capability detection (`hasSystemFeature`) is not implemented.** "Can this
   phone make video calls?" has no evidence source.
8. **`click` and `type_text` have no equivalent user-intent authorisation.**
   They are gated by screen evidence and payload provenance, but a
   model-proposed tap on a present, non-irreversible element still executes
   unrequested. Same class of gap, one layer over.
9. **The step budget is unresolved.** The open-ended path auto-continues every
   1000 ms with no cap on consecutive un-confirmed actions.
10. **GAP-3 / TARGET_NOT_VISIBLE is unresolved.** Neither schema can express
    "the target is not visible, scroll first".
11. **Evidence-grounded is 12/15, not 15/15.** Three cases remain ungrounded.
12. **The LIVE dataset is small (15 synthetic cases) and directional.** Single
    run, no variance measurement. These are not precise percentages, and the
    fixtures are hand-authored, not captured traffic.

## Privacy

Collected: labels and package names of apps already visible under `<queries>`.
Sent to the model: a compact capped list, only when device context is enabled.

Never collected or committed: a real user's installed-app inventory, usage data,
or anything outside the allow-list. The only phone-shaped value in the fixtures
(`9876500000`) is deliberately fake and exists to test that model-invented values
are rejected.
