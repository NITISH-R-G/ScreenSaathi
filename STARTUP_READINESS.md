# Startup readiness

Status of ScreenSaathi against what frontier-model startup programs actually
check. Assessed 2026-08-14.

Legend — **PASS** verified in this repository · **PARTIAL** exists but
incomplete · **BLOCKED** cannot proceed until something else lands ·
**EXTERNAL** requires founder action that no code can satisfy.

An external eligibility condition is never marked PASS because the repository
looks good. That distinction is the entire point of this file.

---

## Product

| Item | Status | Evidence |
| --- | --- | --- |
| Working MVP | **PASS** | Release `v0.1.0`, APK + SHA256, verified on a physical device |
| Runs without any AI credential | **PASS** | `UnavailableProvider` ships; accessibility-only path fully functional |
| Differentiated capability | **PASS** | Accessibility-grounded circle-to-understand + agentic handoff; verified on device |
| Demo video / GIF | **PARTIAL** | Real screenshots exist; no recorded demo |
| Clear business model | **EXTERNAL** | Not defined. Founder decision, not a code artefact |

## Technical

| Item | Status | Evidence |
| --- | --- | --- |
| Provider-agnostic AI architecture | **PASS** | `ai/AIProvider.kt` — no vendor in any signature |
| Model routing | **PASS** | `ai/ModelRouter.kt`, 10 tests |
| Hybrid perception (tree → pixels → VLM) | **PASS** | `ai/PerceptionStrategy.kt`, 8 tests |
| Rich multimodal context (not a bare crop) | **PASS** | `ai/VisionRequest.kt` |
| Gemini provider | **BLOCKED** | Interface ready; no implementation, no key. Claiming otherwise would be false |
| OpenAI provider | **BLOCKED** | Same |
| Anthropic provider | **BLOCKED** | Same — and Anthropic's program weighs *genuine* Claude usage, so a stub actively hurts |
| Cohere provider | **BLOCKED** | Same |
| Bedrock provider | **BLOCKED** | Requires the backend in `docs/AWS.md`, which does not exist yet |
| Unit tests | **PASS** | 214 tests, 0 failures |
| CI | **PASS** | Build, test, lint, secret scan on every push |

## Security

| Item | Status | Evidence |
| --- | --- | --- |
| No secrets in Git | **PASS** | `local.properties` gitignored; CI secret-scan step; branch diff scanned clean |
| No secrets required to build | **PASS** | Builds and runs with no key |
| No production credentials in `BuildConfig` | **PARTIAL** | Only `sarvam.api.key`, optional and developer-supplied. **A key in a distributable APK is extractable** — documented, not solved |
| Client-side key risk documented | **PASS** | See `docs/AI_PROVIDERS.md` and `docs/AWS.md` |
| Ephemeral-token backend for production | **BLOCKED** | Designed in `docs/AWS.md`, not built |

## Privacy

| Item | Status | Evidence |
| --- | --- | --- |
| Data-handling documentation | **PASS** | `docs/DATA_HANDLING.md` |
| Screenshot capture documented | **PASS** | On-demand only, per selection; rationale in the a11y config |
| Crop retention bounded | **PASS** | Released on new selection; verified 10 selections → 1 file |
| No analytics/telemetry | **PASS** | None in the dependency list, by design |
| User can disable AI features | **PASS** by default | No provider configured = no data leaves the device |

## Safety

| Item | Status | Evidence |
| --- | --- | --- |
| Action policy | **PASS** | `session/ActionPolicy.kt`, 10 tests |
| High-risk actions gated | **PASS** | Payments/transfers require confirmation |
| Security surfaces blocked outright | **PASS** | Permissions, factory reset, recovery phrases |
| Model cannot escalate its own permission | **PASS** | Policy is deterministic, evaluated after the model |
| No gesture execution | **PASS** (by design) | `canPerformGestures` deliberately omitted; the user is the irreversible step |

## Open source

| Item | Status |
| --- | --- |
| README, ARCHITECTURE, DECISIONS, DEVELOPMENT, TROUBLESHOOTING, DEMO | **PASS** |
| AGENTS.md for AI agents | **PASS** |
| ROADMAP | **PASS** |
| CONTRIBUTING with good-first-contribution paths | **PASS** |
| `good first issue` on the tracker | **PASS** | Issue #13 |
| LICENSE consistent | **PASS** | MIT, single copyright holder |
| SECURITY.md, CODE_OF_CONDUCT.md, issue/PR templates | **PASS** |
| `docs/AI_PROVIDERS.md` | **PASS** |

## Benchmarks

| Item | Status |
| --- | --- |
| Eval harness | **PASS** — pre-existing framework under `app/src/test/.../evals/` |
| ScreenSaathi-specific categories | **PARTIAL** — selection/intent/policy covered by unit tests; screen-understanding and multilingual datasets are thin |
| Cross-provider comparison | **BLOCKED** — needs ≥2 working providers |
| Provider scorecard | **PARTIAL** — table defined in `docs/AI_PROVIDERS.md`, no rows |

## Website

| Item | Status |
| --- | --- |
| Landing page | **BLOCKED** — required by AWS Activate, requested by Anthropic and Google |
| Domain | **EXTERNAL** |
| Business email on that domain | **EXTERNAL** |

## Business / legal

| Item | Status |
| --- | --- |
| Incorporated entity | **EXTERNAL** |
| Institutional equity funding | **EXTERNAL** — gates Google AI track, Anthropic credits, OpenAI |
| Business model | **EXTERNAL** |
| Team page / founder identity | **EXTERNAL** |

## Applications

| Program | Status | Blocker |
| --- | --- | --- |
| AWS Activate (Founders) | **EXTERNAL** | Website + business email. **No funding requirement — closest to reachable** |
| Cohere Catalyst Grants | **EXTERNAL** | Must apply as an academic/civic/public-impact org |
| Google for Startups (AI track) | **EXTERNAL** | Qualifying VC funding, seed–Series A |
| Anthropic Claude for Startups | **EXTERNAL** | Institutional equity funding; plus a real Claude integration |
| OpenAI for Startups | **EXTERNAL** | Invite/partner-routed only |

Details and sources: `docs/STARTUP_PROGRAMS.md`.

---

## The honest summary

The **repository** is in good shape: tested, documented, safe by construction,
provider-agnostic, and genuinely working on hardware.

The **company** does not exist. Every program above gates primarily on company
facts — incorporation, funding, a domain, a business email. No amount of
further engineering moves those.

Highest-leverage next steps, in order:

1. **Website + domain + business email.** Unblocks AWS Activate outright and is
   requested by every other program. The only external blocker code can help
   with.
2. **One real provider implementation, end to end.** Turns four **BLOCKED**
   rows into **PASS**, makes the benchmark meaningful, and is prerequisite to
   claiming Gemini-native or Claude-native anything.
3. **A recorded demo.** Every application asks what it does; 40 seconds of
   video answers better than any paragraph.
4. Founder decisions on incorporation and funding — which determine whether
   the remaining three programs are reachable at all.
