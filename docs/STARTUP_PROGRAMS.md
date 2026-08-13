# Startup program eligibility

Researched 2026-08-14 against official program pages where reachable, and
reputable secondary sources where the official page could not be fetched in
full (each such case is marked **unverified** below — re-check before relying
on it).

**Read this first.** Most of these programs gate on *company* facts, not
product quality: incorporation, institutional equity funding, a business
email on a company domain, and a live website. ScreenSaathi today is an
open-source project with no incorporated company, no funding, and no website.
No amount of engineering changes that. This document separates what the
repository can fix from what only the founder can.

Nothing here asserts that ScreenSaathi has been accepted to, applied to, or
is currently eligible for any program.

---

## Summary

| Program | Realistically reachable now? | Hard blocker |
| --- | --- | --- |
| AWS Activate (Founders tier) | **Closest to reachable** | Website + business email on that domain |
| Cohere Labs Catalyst Grants | **Possibly reachable** | Must apply as an academic/civic/public-impact org |
| Google for Startups Cloud (AI track) | No | Requires qualifying VC funding (seed–Series A) |
| Anthropic / Claude for Startups (credits tier) | No | Requires institutional equity funding |
| OpenAI for Startups | No | Invite/partner-routed via VC or accelerator |

The two reachable ones both require **a live website**, which is the single
highest-leverage external unblocker and *is* solvable from this repository.

---

## AWS Activate — Founders tier

- **URL:** https://aws.amazon.com/activate/
- **Provides:** ~$1,000 credits at the Founders tier; higher tiers ($100k–$300k) exist via the Portfolio track.
- **Eligibility (Founders):** founded within the last 10 years, pre-Series B, self-funded/bootstrapped, not affiliated with an AWS Activate Provider, no prior equal-or-greater Activate credits.
- **Explicit requirements:** a **fully functioning company website**, an active AWS account tied to the startup, and a **business email address matching the website domain**.
- **ScreenSaathi satisfies:** working MVP, public technical architecture, security and privacy documentation, open-source repository, published release.
- **Missing (repo-solvable):** a live website. See `docs/AWS.md` for the production architecture this program would fund.
- **Missing (external — founder only):**
  - [ ] Register a domain.
  - [ ] Set up a business email on that domain (not gmail.com).
  - [ ] Decide whether to incorporate (Activate Founders does not require incorporation in all regions — **verify for India before applying**).
  - [ ] Create an AWS account tied to the startup.
- **Verdict:** **EXTERNAL** — no funding requirement, so this is the most achievable program. Blocked only on website + business email.
- Source detail is secondary (**unverified** against the official page); confirm tier amounts and the India-specific incorporation rule at the URL above before applying.

## Cohere Labs — Catalyst Grants

- **URL:** https://cohere.com/research/grants
- **Provides:** Cohere API credits; rolling applications; no publication approval required (except AI-safety research).
- **Eligibility:** **academic partners, civic organizations, and public-impact organizations**. Projects must be **for public benefit or open science research**. Focus areas: education, healthcare, climate, sustainability.
- **ScreenSaathi satisfies:** genuine accessibility/public-benefit purpose, open-source under MIT, reproducible evaluation harness, multilingual scope (Hindi/Tamil/English).
- **Tension to be honest about:** the program targets *academic and civic institutions*, not commercial startups. Applying as a for-profit startup is likely a poor fit; applying via a university affiliation is the credible route.
- **Missing (external — founder only):**
  - [ ] Establish an academic or civic affiliation to apply under (e.g. the college named in `PROPOSAL.md`), or a non-profit vehicle.
  - [ ] Write the public-benefit framing — `docs/IMPACT.md` in this repo is the raw material.
- **Verdict:** **EXTERNAL** — plausible on subject matter, blocked on organisational identity.

## Google for Startups Cloud Program

- **URL:** https://cloud.google.com/startup/benefits · AI track: https://cloud.google.com/startup/ai
- **Provides:** up to $200,000 (standard) or **$350,000 (AI track)** in Google Cloud credits, typically valid 2 years.
- **Eligibility (general):** under 5 years old, less than Series A funding, not a previous GCP program participant.
- **Eligibility (AI track):** must **use or plan to use Vertex AI or Gemini as the foundation of the primary product**, have **received qualifying venture capital funding (seed to Series A)**, founded within the last 10 years, and not already received >$5,000 in Google Cloud credits. SAFEs count as equity funding; **government grants, crowdfunding, angel, and friends-and-family do not**.
- **ScreenSaathi satisfies:** working MVP, clear technical architecture, and — once `GeminiProvider` is genuinely implemented and tested — a real Gemini-based path.
- **Missing (external — founder only):**
  - [ ] **Qualifying VC funding (seed–Series A).** This is the hard gate for the AI track.
  - [ ] Incorporated company.
  - [ ] Website and business email.
- **Verdict:** **BLOCKED (external)** on the AI track until there is institutional equity funding. Re-check the standard track's exact funding floor at the official URL — the "less than Series A" phrasing is **unverified** and may admit unfunded startups at a lower credit tier.

## Anthropic — Claude for Startups

- **URL:** https://claude.com/programs/startups
- **Provides:** free credits and **priority rate limits** (highest tier). Standard API pricing resumes automatically afterwards. Credit amounts are not published on the official page.
- **Eligibility (verified from the official page):** company must have **received equity funding from an institutional investor**, been **founded within the last four years**, and **not previously received Anthropic startup credits**. The page also states the program accepts founders "with or without VC backing," with VC-backed companies eligible for additional benefits — these two statements coexist on the page, so the practical floor is worth confirming directly.
- **Application asks for:** a Claude Console account (API console, not the chat app), a **company email**, a **company website**, and a short description of what you're building.
- **ScreenSaathi satisfies:** a genuinely agentic product, real technical differentiation, open-source credibility.
- **Missing (repo-solvable):** an actual Claude integration. Anthropic's program weighs real Claude usage — shipping an `AnthropicProvider` that is only an unimplemented interface would be a false claim, and is documented as such in `docs/AI_PROVIDERS.md`.
- **Missing (external — founder only):**
  - [ ] Institutional equity funding.
  - [ ] Company email + website.
- **Verdict:** **BLOCKED (external)** on funding for the credits tier.

## OpenAI for Startups

- **URL:** https://openai.com/form/startup-program/ (**verify** — the public entry point has changed repeatedly)
- **Provides:** commonly ~$2,500 standard, up to $5,000+, and $25,000+ within approved accelerator cohorts.
- **Eligibility:** **invite-based / partner-routed.** In 2026 there is no broad public self-serve credits program; access is typically via a Ramp account (~$2,500) or through OpenAI's VC and accelerator partner network (Y Combinator and similar bundle larger allocations).
- **Credits expire ~12 months after issuance; OpenAI does not extend expiry.**
- **Missing (external — founder only):**
  - [ ] A VC/accelerator partner relationship, or a Ramp account.
- **Verdict:** **BLOCKED (external)** — not an open application. All amounts here are **unverified** secondary-source figures.

---

## What the repository can actually do about this

Ranked by leverage:

1. **Ship a real website.** Required by AWS Activate, requested by Anthropic and Google. This is the only hard requirement on the list that code can satisfy. See `docs/WEBSITE.md`.
2. **Make the Gemini/Claude/OpenAI paths real, not decorative.** Google's AI track wants Gemini to be foundational; Anthropic weighs genuine Claude usage. A provider interface with no working implementation satisfies neither and is dishonest to claim. Status per provider is tracked in `docs/AI_PROVIDERS.md`.
3. **Keep the impact measurable.** `docs/IMPACT.md` defines the metric framework; `evals/` produces the numbers. Cohere's public-benefit framing and every program's "what does it do" question are better answered with a reproducible benchmark than with adjectives.
4. **Stay honest.** Every program above asks for a company website and description. Overstating capability there is the failure mode that actually gets applications rejected on contact with a working demo.

## Founder action checklist (nothing here is code)

- [ ] Decide on incorporation (entity type, jurisdiction).
- [ ] Register a domain.
- [ ] Business email on that domain.
- [ ] Publish the website (repo can generate it; founder must point DNS).
- [ ] Create AWS account, Claude Console account, Google Cloud account as needed.
- [ ] Decide whether to pursue funding — this is the gate on Google AI track, Anthropic credits, and OpenAI.
- [ ] For Cohere: secure an academic/civic affiliation to apply under.

## Sources

- [Google Cloud — Startups program benefits](https://cloud.google.com/startup/benefits)
- [Google Cloud — AI startups program](https://cloud.google.com/startup/ai)
- [Claude for Startups (official)](https://claude.com/programs/startups)
- [Cohere Labs — Catalyst Grants (official)](https://cohere.com/research/grants)
- [AWS Activate](https://aws.amazon.com/activate/)
- Secondary, for figures marked **unverified**: [Startup Offers — Anthropic](https://guptadeepak.com/startup-offers/guides/anthropic-for-startups), [Startup Offers — OpenAI](https://guptadeepak.com/startup-offers/guides/openai-for-startups), [CloudKompas — AWS Activate 2026](https://cloudkompas.com/blog/aws-activate-complete-guide-2026), [CloudKompas — Google Cloud 2026](https://cloudkompas.com/blog/google-cloud-for-startups-2026-credits-guide)
