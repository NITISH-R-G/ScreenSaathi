# ScreenSaathi landing page

A single self-contained `index.html` plus four real device screenshots. No
build step, no framework, no external requests — no web fonts, no CDN, no
analytics. It can be served from any static host.

## Run it locally

```bash
npx serve site
```

Or open `site/index.html` directly; images resolve relatively.

## Deploying to GitHub Pages

Settings → Pages → Build and deployment → **GitHub Actions**. The workflow at
`.github/workflows/pages.yml` publishes this folder on every push to `main`.

The `<link rel="canonical">` and the OpenGraph URLs in `index.html` currently
point at `https://nitish-r-g.github.io/ScreenSaathi/`. **Update those three
absolute URLs if the site is served from a custom domain**, otherwise social
previews and canonical will point at the wrong host.

## Design system

The mark is a ring, because a ring is literally what the product draws on
screen. Concentric circles built from one repeated unit.

The gradient is not decoration — each hue is a real state in the architecture,
and colour moves through the page in that order exactly once:

| Token | Hue | Stage |
| --- | --- | --- |
| `--perceive` | `#3D6BFF` | reading the accessibility tree |
| `--reason` | `#7C5CFF` | `PerceptionStrategy` / `ModelRouter` |
| `--act` | `#FF7A2F` | highlight and guide |

Most surfaces stay neutral (`#0B0B12`, `#13131C`). Saturation is reserved for
state, so it still means something when it appears.

Typefaces are the system stack deliberately — zero network requests, and it
renders in the reader's own UI font, which suits a product about legibility.

## Content rules

Everything on the page is verifiable from the repository:

- Screenshots are unmodified captures from a physical device. `hero.png` and
  `target-highlighting.png` in `docs/assets/` are byte-identical, so only one
  is used rather than presenting them as two states.
- Languages are labelled **supported** only where the assistant's own wording
  is authored (English, Hindi, Tamil). Everything else is marked *planned*.
- Vision is described as designed but **not integrated**, because no provider
  is implemented.
- No usage numbers, benchmarks, customers, funding or testimonials appear,
  because none exist.
- A disclaimer states the project is independent and unaffiliated with any
  company named or shown.

Keep it that way. If a claim on this page stops being true in the code, the
page is wrong, not the code.

## Accessibility

- All body text measured ≥ 4.5:1 with the ancestor chain composited (the
  dimmest token lands at ~5.6:1).
- Touch targets padded to ≥ 38px under `pointer: coarse`.
- Full keyboard navigation with arrow-key support on the state tabs, visible
  focus rings, and a skip link.
- `prefers-reduced-motion` disables the auto-advancing showcase and all
  transitions.
- Semantic landmarks, real `alt` text, `lang` attributes switched per language
  example.
