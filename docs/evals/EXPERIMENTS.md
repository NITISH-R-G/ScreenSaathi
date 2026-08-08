# Experiments

One row per experiment. An experiment is only "done" when it has a decision.

Record: hypothesis, exactly what changed, baseline compared against, dataset +
prompt + model + evaluator version, metric deltas, regressions, decision.

## Rules

- Compare against a recorded baseline (`evals/baselines/`), never against
  "how it felt".
- Change **one** thing. Prompt *and* temperature together explains nothing.
- A win on one metric does not license a critical regression — see the quality
  gate in `README.md`.
- The evaluator-optimizer loop may **propose** a change. It may never install
  one. Production prompt/model changes require a human PR review. An optimizer
  that can edit the thing it is scored on will optimize the scorer.

## Log

| ID | Hypothesis | Change | Baseline | Result | Decision |
|---|---|---|---|---|---|
| BASE-001 | — | Initial state, no change | — | OFFLINE golden 8/8, 0 critical. Adversarial 3/8 — 2 CRITICAL, 1 MAJOR, 2 MINOR gaps found | Recorded as baseline |
| EXP-001 | An irreversible-step guard fixes GAP-1 with no model change | `TaskStep.irreversible` + `SafetyGuard.blocksIrreversibleJump`, wired into `SessionController` before `jumpTo` | BASE-001 | `adv_008` CRITICAL cleared. Golden 8/8 unchanged — `pb_004` ("skip to the payment" on a *filled* form) still passes, so the guard does not over-block | **ACCEPT** |
| EXP-002 | Grounding the plan in screen evidence fixes GAP-2 | `SafetyGuard.blocksUngroundedPlan`: refuse when the snapshot is empty or the target does not resolve | BASE-001 | `adv_005` passes outright. Golden 8/8 unchanged | **ACCEPT** |

Combined effect: adversarial 3/8 → 4/8, **critical failures 2 → 0**, golden
unchanged at 8/8. No metric regressed, so the quality gate is satisfied without
a documented trade-off.

Both fixes are deterministic and add no latency and no model call. Neither
touches the prompt — worth noting, because the ladder in `OPTIMIZATION.md`
predicted exactly this: the defects were missing guards, not model weakness.

## Queued (in priority order, derived from actual failures)

**EXP-003 — LIVE baseline.**
Hypothesis: none — this establishes the missing model baseline.
Measure: LIVE golden pass rate, step accuracy, p50 latency vs the 700 ms budget.
Blocked on: `SARVAM_API_KEY` in the eval environment.

**EXP-004 — instruction shape in the prompt.**
Hypothesis: adding an explicit "one action, no technical words, under 14 words"
rule to `planner_v1.md` fixes GAP-4 at the source.
Measure: `adv_006`, `adv_007` under LIVE. Requires EXP-003 first.
