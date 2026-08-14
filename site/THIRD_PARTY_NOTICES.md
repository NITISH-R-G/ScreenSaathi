# Third-party notices — ScreenSaathi website

This page (`site/`) ships **no third-party code**. Everything in `index.html`
is first-party: hand-written CSS and vanilla JavaScript, no dependencies, no
build step, no runtime downloads. There is nothing here to attribute.

That is a deliberate outcome, not an oversight. This file records the
component libraries that were evaluated for the motion redesign, what their
licences actually permit, and why each one was or was not used.

## Evaluation record

Inspected 2026-08-14 against the live sites and repositories.

### SmoothUI — https://smoothui.dev

| | |
|---|---|
| Licence | MIT — `Copyright (c) 2024 Eduardo Calvo` (verified in the repo's `LICENSE`) |
| Source available | Yes, public repository |
| Install mechanism | `npx shadcn@latest add @smoothui/<component>` |
| Dependencies | React 19, TypeScript, Tailwind CSS v4, Motion (ex-Framer Motion), GSAP |
| Modification allowed | Yes |
| Commercial use | Yes |
| Attribution required | Yes — MIT notice must be retained if code is used |
| **Used here** | **No** |

**Why not:** the licence is fully compatible with this repository's MIT
licence — this was rejected purely on installation mechanism. Every component
is a React `.tsx` module consumed through the shadcn CLI and compiled by a
bundler. This site is a single static HTML file deployed by
`.github/workflows/pages.yml` with no build step, and the project constraint
is explicitly *no npm, no framework, no bundler*. Adding React 19 + Tailwind v4
+ Motion + GSAP to render one landing page would mean introducing a toolchain,
rewriting the deployment workflow, and shipping several hundred KB of runtime
to replace roughly 14 KB of hand-written vanilla motion code.

### Unlumen UI — https://ui.unlumen.com

| | |
|---|---|
| Licence | Not published for the free tier; Pro tier is proprietary |
| Source available | Free components via the registry; Pro gated |
| Install mechanism | shadcn CLI (`init` + `add`), same as above |
| Dependencies | React / Next.js, Tailwind, Motion |
| Pro components | Require an `UNLUMEN_LICENSE_KEY` env var, passed as an Authorization header or query parameter to the registry |
| **Used here** | **No** |

**Why not:** two independent blockers. First, the same React/shadcn/bundler
requirement as SmoothUI. Second, and more importantly, **no licence text is
published for the free components** — "free to install" is not a grant of
rights, and shipping code into an MIT-licensed repository without a known
licence is not something to guess at.

The Pro components (Notion Floating Action Menu, Questionnaire, Social Hover
Cards, Animated Input, Pixel Scroll Transition, Stacked Feature Cards, Video
Slider, and others) are paid and key-gated. They were not used, and no attempt
was made to extract their implementations to work around the licence key — the
key *is* the licence boundary, and stepping around it would be
misappropriation regardless of how easy it might be.

Free components that were reviewed for ideas — Animated List, Motion
Navigation Menu, Hover Feature Cards, Progressive Blur, Dock, Aurora Bars,
Side By Side Slide — informed *interaction patterns only* (a morphing nav, a
progressive-blur masthead, staggered list entrance). Those patterns are common
web-UI vocabulary, not protected expression; no Unlumen source was copied.

### animmasterlib — https://animmasterlib.dev

| | |
|---|---|
| Licence | Proprietary, commercial |
| Cost | $4.99 (PRO) / $8 (Premium) one-time |
| Source available | Only after purchase, delivered via a Google Drive folder |
| Composition | ~60% HTML/CSS/JS, ~30% React, ~10% Next.js |
| **Used here** | **No** |

**Why not:** it is a paid asset pack. The components are not licensed for
redistribution inside an MIT-licensed open-source repository, and this project
has not purchased it. Technically its vanilla-JS half would have been the
easiest of the four to drop into a no-build site; the licence is what rules it
out, not the tech.

### oil-motion — https://github.com/oil-oil/oil-motion

| | |
|---|---|
| Licence | MIT |
| Source available | Yes |
| Install mechanism | Installed as an *AI agent skill*, not a package |
| Dependencies | External video-generation service; requires user-supplied API keys |
| **Used here** | **No** |

**Why not:** the licence is fine, but this is not a component library. It is a
pipeline that generates interpolated **video** between keyframes and ships the
result as alpha-WebP image sequences or green-screen MP4. Using it would mean
adding megabytes of generated video to a repository that currently serves a
1.1 MB page, making the site's motion un-editable except by re-running a paid
generation service, and depending on a third-party API key at authoring time.
For scroll-driven interface motion, a spring integrator in 40 lines of
JavaScript is the better engineering answer.

## What the site uses instead

| Capability | Implementation |
|---|---|
| Spring physics | First-party critically-damped spring integrator, one `requestAnimationFrame` loop |
| Scroll choreography | `IntersectionObserver` for reveals; a single rAF scroll orchestrator for continuous values |
| Continuous spectrum | One CSS custom property (`--flow`) recomputed per frame and inherited by every element that expresses state |
| Progressive blur | Layered `backdrop-filter` with a mask gradient |
| Text streaming | `requestAnimationFrame` character scheduler |
| Ring geometry | Inline SVG generated from one repeated unit rotated around a centre |
| Reduced motion | `prefers-reduced-motion` gates every loop; static end-states are rendered instead |

Total motion code: one `<script>` block, no network requests, no runtime
dependencies.

## Fonts, icons, images

- **Fonts** — system font stacks only (`ui-sans-serif`, `ui-monospace` and
  platform fallbacks). No downloaded webfonts, no Google Fonts, no CDN.
- **Icons** — hand-authored inline SVG paths. The GitHub mark is GitHub's
  logo, used to link to a GitHub repository, which is its intended nominative
  use.
- **Screenshots** — unmodified captures from a physical Android device running
  ScreenSaathi. Third-party application interfaces visible inside them (Uber,
  and app icons on the launcher screen) remain the property of their
  respective owners and appear solely to show ScreenSaathi operating on real
  software.

## Design influence

The visual system — a continuous blue→violet→orange spectrum treated as flow
rather than as three separate accent colours, mandala-style construction from
one repeated geometric unit, restrained saturation over deep neutral surfaces
— is influenced by publicly described brand principles, including Sarvam AI's.

No Sarvam asset, logo, wordmark, font or source code is used or reproduced
here, and no affiliation, sponsorship or endorsement is implied. ScreenSaathi
is an independent open-source project. It calls Sarvam's public speech and
language APIs, which is a customer relationship and nothing more.

The ring is ScreenSaathi's own mark, and it is not arbitrary: a ring is
literally what the product draws on screen.
