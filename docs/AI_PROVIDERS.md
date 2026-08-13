# AI providers

How ScreenSaathi talks to models, how to add one, and — importantly — which
ones actually work today.

## Current status

| Provider | Text | Vision | Realtime | Status |
| --- | --- | --- | --- | --- |
| Sarvam | ✅ planner | — | — | **Working.** STT/planner/TTS, driven directly by `SessionController` |
| On-device | ✅ intent | ✅ tree | — | **Working.** `TargetResolver`, `IntentClassifier`, `SelectionResolver` |
| Gemini | — | — | — | **Interface only.** Not implemented |
| OpenAI | — | — | — | **Interface only.** Not implemented |
| Anthropic | — | — | — | **Interface only.** Not implemented |
| Cohere | — | — | — | **Interface only.** Not implemented |
| Bedrock | — | — | — | **Interface only.** Needs a backend |

"Interface only" means exactly that: the abstraction accepts such a provider,
and none has been written. ScreenSaathi does not use Gemini, GPT, Claude or
Command today, and nothing in this repository should be read as claiming it
does.

**The app is fully functional with none of them configured.** That is the
shipping configuration.

## Architecture

```
SessionController / CircleSession
        │
        ▼
   ModelRouter ──── routes by task shape
        │
        ├── OnDevice          target resolution, intent (no model, no network)
        └── ProviderRegistry
              ├── TextProvider
              ├── VisionCapableProvider
              ├── RealtimeProvider
              ├── EmbeddingProvider
              └── SearchProvider
                     │
                     └── UnavailableProvider  ← what ships
```

Two design rules worth keeping:

**No vendor names in any signature.** Not in `AIProvider`, not in
`VisionRequest`, not in `ModelRouter`. A chat-completions shape, a particular
auth scheme, streaming semantics — these are vendor accidents, and encoding
them is how a codebase becomes unable to switch.

**The cheapest correct path wins.** `PerceptionStrategy` sends nothing
off-device when the accessibility tree can answer, which is the common case.
That is simultaneously the privacy, cost, latency and explainability win. A
provider is an escalation, not a default.

## Perception levels

| Level | Uses | When |
| --- | --- | --- |
| `ACCESSIBILITY_ONLY` | Tree only | A confidently labelled control, or readable text. **No pixels leave the device** |
| `HYBRID` | Tree + crop | Weak or ambiguous match — pixels break the tie, tree context comes along |
| `VISION_ONLY` | Crop | No accessibility semantics at all — an image, icon or canvas |

`VISION_ONLY` with no provider configured is reported honestly to the user
rather than guessed at. See `PerceptionStrategy.isSatisfiable`.

## What a vision provider receives

Never a bare crop. `VisionRequest` carries pixels **plus** the resolved
element and its confidence, the text inside the selection, nearby text, the
full screen rendering, the foreground package, prior turns against the same
selection, the active task, and the target language.

A model given only a crop has to guess. Guessing is the failure mode this
product cannot afford — its users frequently cannot verify the answer.

## Adding a provider

1. Implement the narrow capability you need (`TextProvider` or
   `VisionCapableProvider`) — not a fat interface.
2. Return `ProviderResult.Unavailable` rather than throwing; every call site
   degrades.
3. Read credentials from `local.properties` via `BuildConfig`. **Never commit
   a key.** See `local.properties.example`.
4. Register it in `ProviderRegistry`.
5. Add it to the scorecard below by running `evals/`.

Nothing else should need to change — not `CircleSelectionView`, not
`SelectionResolver`, not `SessionController`, not `OverlayService`.

## API keys: the honest security position

A key compiled into a distributable APK **is extractable**. `BuildConfig`
obfuscates nothing. This is true of the existing optional `sarvam.api.key` and
would be true of any provider key added the same way.

- **Acceptable** for local development and demo builds from source.
- **Not acceptable** for a public release doing real spend.

The production shape is a backend holding the credential and issuing
short-lived tokens to the app — see `docs/AWS.md`. Until that exists, treat any
key in a built APK as compromised on distribution.

## Provider scorecard

To be filled by running the same ScreenSaathi tasks across providers. **No rows
yet — no provider is implemented.** Inventing numbers here would make the whole
benchmark worthless.

| Provider | Target grounding | Visual ID | Agent success | Hallucination | p50 latency | Cost/1k | Multilingual |
| --- | --- | --- | --- | --- | --- | --- | --- |
| _(none implemented)_ | | | | | | | |

The question this table exists to answer is not "which model is best" but
**"which model makes ScreenSaathi best at guiding someone through Android"** —
which generic benchmarks do not measure.

## Testing without a provider

`UnavailableProvider` and the fakes in `ModelRouterTest` cover the degradation
paths. Routing and escalation policy are pure logic and fully unit-tested
(18 tests across `ai/`), so provider-selection behaviour is verifiable with no
network and no credential.
