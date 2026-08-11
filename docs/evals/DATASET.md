# Datasets

```
evals/
  datasets/
    golden/planner_v1.jsonl        gated, must not regress
    adversarial/planner_v1.jsonl   reported; some cases fail on purpose
  baselines/planner_baseline_v1.json
  schema/eval_case.schema.json
```

Development and regression sets are **not yet created**: with 16 cases total,
splitting further would produce sets too small to mean anything. Golden serves
as the regression set today. Split when the corpus passes ~50 cases.

## Provenance — read this before trusting a number

Every case is **synthetic and hand-authored**. There is no captured production
traffic and no real user data of any kind.

- Screens are transcribed from the real `DemoTaskActivity` layout
  (`amount_field`, `account_field`, `submit_button`) and the real `pay_bill`
  task DSL, so element ids and structure are accurate.
- `recorded_args` are **hand-written to represent plausible model behaviour**.
  They are replayed through the production `SarvamPlanner.parse()`, so the
  *parser* under test is real even though the *response* is authored.

This means OFFLINE results measure the parse and policy layer honestly, and say
nothing about model quality. Only LIVE mode measures the model.

## Ground-truth rule

The golden set is ground truth. It is **never** regenerated from model output.
When a golden case fails, exactly one of two things is true — the system
regressed, or the case was wrong — and deciding which is a human's job. Editing
a case to match new output silently destroys the baseline.

## Case format

JSONL, one case per line, `//` comments allowed. Schema:
`evals/schema/eval_case.schema.json`.

Key fields: `case_id`, `task` (inlined DSL so cases are self-contained),
`screen` (accessibility elements), `expected` (`acceptable_steps`,
`target_resource_id`, `language`, `must_resolve_target`,
`max_instruction_words`, `expect_rejection`), `safety` (`must_not_step`,
`requires_uncertainty`, `max_confidence`), and either `recorded_args` (object,
wrapped into an API envelope by the loader) or `recorded_raw` (verbatim, for
malformed-envelope cases).

## What must be collected next

To unlock the evaluations that cannot run today:

1. **Real eSanjeevani screen dumps.** Install the app, walk the booking flow,
   capture `ScreenReaderService.snapshot()` output at each stage. Without these
   there is no eSanjeevani dataset — and no honest claim about that use case.
   *Check the app's terms before redistributing captured UI structure; commit
   element ids and layout only, never entered values.*
2. **An eSanjeevani task DSL** (`app/src/main/assets/tasks/`), authored against
   those real ids.
3. **LIVE planner responses** across all three languages, to establish a model
   baseline rather than a parser baseline.
4. **Open-ended cases** for `planOpenEnded()`, which can launch apps, tap and
   type and is currently unevaluated (see `ARCHITECTURE_ASSESSMENT.md`).
5. **Degraded screens**: dark mode, large accessibility font, keyboard covering
   the target, rotation, partially-loaded views. Each needs a real snapshot to
   be meaningful — inventing them would only test the invention.

## Never in a dataset

Patient information, phone numbers, OTPs, account numbers, credentials, API
keys, or raw screenshots of a real person's device. The account numbers in the
fixtures (`100234567`) are obviously fake and deliberately so.
