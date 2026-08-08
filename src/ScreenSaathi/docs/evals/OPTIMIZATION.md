# The optimization ladder

Start simple. Climb only when an eval failure proves the current rung is
exhausted. Every optimization must answer one question:

> **Which eval failure does this solve?**

If there is no failing case, there is nothing to optimize — only something to
break.

| # | Rung | Status for ScreenSaathi |
|---|---|---|
| 1 | Clearer prompt | **Next.** GAP-4 (instruction shape) is a prompt problem — but needs a LIVE baseline first (EXP-003) |
| 2 | Role / system instructions | `planner_v1.md` exists. `planOpenEnded()`'s prompt is a hardcoded string and must be extracted before it can be versioned or evaluated |
| 3 | Better input structure | Already structured: `toPromptText()` renders indexed elements with flags |
| 4 | Structured outputs | **Already done** — forced tool call, frozen schema, prose rejected |
| 5 | Reasoning | Deliberately off (`reasoning_effort: null`) for the 700 ms budget. No failure yet suggests turning it on |
| 6 | Few-shot | No evidence needed yet |
| 7 | Temperature / config | Fixed at 0.1, never swept. Cheap experiment once LIVE exists |
| 8 | Tool calling | Already the transport |
| 9 | Call chaining | Rejected — triples latency, no failure attributable to conflating jobs |
| 10 | RAG | Rejected — the context needed is the live screen, not a corpus |
| 11 | Chunking | Rejected — snapshots are far inside context |
| 12 | Agentic loops | `planOpenEnded()` already is one, and is unevaluated. Evaluate before extending |
| 13 | Parallelization | Rejected — no independent operation to overlap |
| 14 | Evaluator-optimizer | Infrastructure documented; may propose, never install |
| 15 | Routing | Rejected — one task type |
| 16 | Fine-tuning | Rejected — no corpus, and the bottleneck is missing guards, not model quality |

## The rung that actually matters here

Rungs 1–16 are all **model** interventions. The three confirmed defects
(`FAILURES.md` GAP-1..3) are **not model problems** — they are missing
deterministic guards. No amount of prompt engineering reliably prevents a model
from occasionally selecting `submit`; a precondition check prevents it always.

Prefer the deterministic fix whenever one exists. It is cheaper, faster, testable,
and it cannot regress on a model update.
