# Startup application material

Reusable, factual copy for program applications. Everything here is either
verifiable from this repository or marked `[FILL IN]`.

**Nothing in this document may be filled in with an estimate.** These fields
end up in applications that are checked. A wrong number is worse than a blank
one, and "we don't have users yet" is a perfectly respectable answer that
"~500 users" is not.

---

## One line

> ScreenSaathi is an open-source Android AI agent that understands the live
> screen and shows people exactly where to tap.

## 50 words

> ScreenSaathi is an open-source Android assistant for people who find modern
> app interfaces hard to navigate. It listens in Hindi, Tamil or English, reads
> the live screen through Android's Accessibility API, resolves the exact
> control the user needs, and rings it — then waits for them to tap it
> themselves.

## 100 words

> ScreenSaathi is an open-source Android AI agent built for people who struggle
> with modern app interfaces — small text, unlabelled icons, buried buttons.
>
> The user speaks naturally, or circles anything on screen. ScreenSaathi reads
> the live accessibility tree, resolves the actual UI element they mean — not a
> hardcoded coordinate — draws a ring around it, and explains the next step in
> the language they spoke.
>
> It guides; it does not tap. The user performs every action, which makes them
> the irreversible step. When it cannot see something, it says so rather than
> guessing — a deliberate choice for users who cannot verify an answer.

## Problem

Modern Android interfaces assume visual fluency that many people do not have:
elderly users, first-time smartphone users, people navigating an unfamiliar
app under pressure. Existing voice assistants answer questions and launch apps
but have no idea what is currently on the screen, so they cannot help with the
actual difficulty — *which thing do I tap?*

## Solution

Screen understanding grounded in the accessibility tree, combined with natural
language and a visual highlight. Circle anything, ask about it in your own
language, and get either an explanation or step-by-step guidance through the
real UI, with the target ringed at each step.

## Why now

Android's Accessibility API can read the live UI, and `takeScreenshot()` (API
30+) can capture pixels without a second consent flow. Multimodal models can
now reason over both. The combination — structured tree *plus* pixels *plus*
user intent — was not practical before.

## Technical differentiation

1. **Accessibility-grounded, not pixels-only.** Circle-to-search products send
   a crop to a model. ScreenSaathi first resolves the selection against the
   live accessibility tree, so it can say "the *Book now* button" rather than
   describing an image. Verified on device against third-party apps.
2. **Hybrid escalation.** `PerceptionStrategy` sends nothing off-device when
   the tree can answer — the common case. Pixels escalate only when the tree
   is weak or absent. This is simultaneously the privacy, cost, latency and
   explainability win.
3. **Provider-agnostic.** No vendor name appears in any AI interface. Switching
   between Gemini, OpenAI, Claude, Cohere or Bedrock is one class, not a
   rewrite.
4. **Refuses rather than hallucinates.** A purely visual selection with no
   vision provider returns an explicit "I'd need visual understanding for
   that." Verified on device.
5. **Policy over model.** `ActionPolicy` gates consequential actions
   deterministically; no model can raise its own permission level.
6. **App-agnostic resolution.** No per-app branches in the resolver.

## AI architecture

Voice (Sarvam STT) → intent → accessibility snapshot → ranked target
resolution → visual highlight → user taps → screen-change detection →
re-perceive → repeat. A deterministic `StepEngine` completes scripted tasks
with no network at all.

Provider layer: `TextProvider`, `VisionCapableProvider`, `RealtimeProvider`,
`EmbeddingProvider`, `SearchProvider`, routed by `ModelRouter`. See
`docs/AI_PROVIDERS.md`.

## Accessibility and multilingual impact

Designed for Hindi, Tamil and English speakers; detects the spoken language
and answers in it. Measurement framework in `docs/IMPACT.md` — **no impact
numbers have been collected**, and the framework exists so that when they are,
they are reproducible.

## Open-source strategy

MIT licensed, public repository, published release with APK and checksum, CI
with build/test/lint/secret-scan, contributor guide with good-first-issue
paths, and `AGENTS.md` for AI coding agents.

## Current stage

Working prototype, verified on a physical Android device. Public release
`v0.1.0`. 214 unit tests. No company, no funding, no users.

## Known limitations (state these; they get found anyway)

- No vision provider configured — purely visual selections are declined, not answered.
- Planner latency is over its own stated budget (see `docs/PARKING_LOT.md`).
- Third-party app guidance matches visible text; copy changes break it.
- Live-microphone Tamil is unverified.
- No session persistence.
- No recorded demo video.

## Fields requiring founder input

| Field | Value |
| --- | --- |
| Legal entity name | `[FILL IN]` |
| Incorporation date / jurisdiction | `[FILL IN]` |
| Website | `[FILL IN]` — **blocks AWS Activate** |
| Business email on company domain | `[FILL IN]` — **blocks AWS Activate** |
| Funding stage | `[FILL IN]` — **gates Google AI track, Anthropic, OpenAI** |
| Investors | `[FILL IN]` — leave blank if none; do not embellish |
| VC / accelerator referral | `[FILL IN]` — leave blank if none |
| Revenue | `[FILL IN]` — likely "pre-revenue" |
| Users / traction | `[FILL IN]` — likely "none yet" |
| Team size and roles | `[FILL IN]` |
| Business model | `[FILL IN]` |
| Demo video URL | `[FILL IN]` |

## Verifiable links

- Repository: https://github.com/NITISH-R-G/ScreenSaathi
- Release: https://github.com/NITISH-R-G/ScreenSaathi/releases/latest
- Architecture: `docs/ARCHITECTURE.md` · Decisions: `docs/DECISIONS.md`
- Privacy: `docs/DATA_HANDLING.md` · Readiness: `STARTUP_READINESS.md`
