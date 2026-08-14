# Production backend architecture

**Nothing in this document is built.** It is the intended production shape,
written because AWS Activate and every other program asks for a deployment
architecture, and because the security problem it solves is real today.

The Android demo requires **no AWS and no backend**. It runs entirely
on-device against the accessibility tree, and that must stay true.

## The problem this solves

A credential compiled into a distributable APK is extractable. `BuildConfig`
is not obfuscation — it is a constant in the DEX. The existing optional
`sarvam.api.key` has this property, and any provider key added the same way
would too.

That is acceptable for a developer building from source. It is not acceptable
for a public release doing metered spend against a frontier model, because
the key is then effectively public and the bill is not.

## Demo architecture (today)

```
ScreenSaathi (Android)
  ├── AccessibilityService ──► screen understanding   [on-device]
  ├── TargetResolver        ──► element matching      [on-device]
  ├── IntentClassifier      ──► question vs action    [on-device]
  └── Sarvam API            ──► STT / planner / TTS   [key from local.properties]
```

No server. No account. Works offline for everything except voice.

## Production architecture (proposed)

```
ScreenSaathi (Android)
        │  short-lived token, no provider key on device
        ▼
API Gateway ──► Lambda (auth + policy + rate limit)
        │
        ├──► Amazon Bedrock ──► Claude / Nova / Llama
        ├──► Gemini API
        └──► OpenAI API
```

Properties that matter:

- **No provider credential ever reaches the device.** The app holds a
  short-lived token; the backend holds the real keys in Secrets Manager.
- **Server-side rate limiting and spend caps**, so a leaked token is bounded
  in blast radius rather than unbounded.
- **Provider choice becomes a server-side decision.** `ModelRouter` already
  treats providers as interchangeable; the backend can route without shipping
  a new APK.
- **Policy is enforced twice.** `ActionPolicy` runs on-device (so it works
  offline) and would run again server-side (so a tampered client cannot bypass
  it).

## What stays on-device permanently

Accessibility-tree reading, target resolution, intent classification, and the
`PerceptionStrategy` decision itself. These are the hot path — routing them
through a network would make ScreenSaathi slower, costlier, less private and
less reliable, for no accuracy gain. See `docs/AI_PROVIDERS.md`.

Screenshots are only ever sent when `PerceptionStrategy` returns
`HYBRID` or `VISION_ONLY`, which is the minority of selections by design.

## Why Bedrock specifically

Bedrock offers multiple model families behind one interface, which matches the
provider-agnostic design rather than fighting it. It is listed as *an* option,
not a commitment — the same backend could front Gemini or OpenAI directly, and
the architecture above shows all three deliberately.

## Cost model

**Unmeasured.** Real figures need the accessibility-only resolution rate from
`docs/IMPACT.md`, which needs real sessions. The architectural claim — that
most selections never reach a model — is testable via `PerceptionStrategy` and
should be measured before any spend projection is made.

## Implementation status

| Component | Status |
| --- | --- |
| Android client | **Built and working** |
| On-device policy + routing | **Built and tested** |
| Backend | **Not started** |
| Bedrock provider | **Not started** |
| Token issuance | **Not started** |

Building this is only worthwhile once there is a provider to put behind it and
a reason to distribute a release that spends money. Until then the demo
architecture is the correct one.
