# Architecture / optimization assessment

For each technique: is it needed **now**, what is the evidence, what eval would
justify it, and the decision. Nothing here is adopted because it is fashionable.

| Technique | Needed now? | Evidence | Eval that would justify it | Decision |
|---|---|---|---|---|
| **Structured outputs** | **Already done** | `SarvamPlanner` forces `tool_choice: required` against a frozen schema; `parse()` rejects prose (`adv_002`) | — | Keep |
| **Clearer prompt** | Maybe | `planner_v1.md` is versioned, but `adv_006`/`adv_007` show no instruction-shape guidance | Golden pass rate + instruction checks in LIVE mode | Try first — cheapest rung |
| **Prompt versioning** | **Partially broken** | `plan()` loads a versioned asset; `planOpenEnded()` uses a hardcoded inline string (`SarvamPlanner.kt:177`) | — | **Extract it.** A prompt that cannot be versioned cannot be evaluated |
| **Deterministic guards** | **Yes** | GAP-1, GAP-2, GAP-3 are all fixable with plain code, no model | Adversarial suite already fails on them | **Highest value.** Do before any model work |
| **Reasoning / CoT** | No | Deliberately disabled (`reasoning_effort: null`) to hold a 700 ms budget; step selection is routing, not reasoning | LIVE step accuracy < ~85% with a good prompt | Rejected for now |
| **Few-shot examples** | Unknown | No evidence current zero-shot selection is weak | LIVE step-accuracy failures clustering on one stage | Defer until LIVE data exists |
| **Temperature sweep** | Maybe | Fixed at 0.1; never swept | Step accuracy + instruction variance across 0.0/0.1/0.3 | Candidate experiment |
| **Model comparison** | Limited | Only Sarvam chat models are wired in. Gemini/OpenAI are **not integrated** — the brief's premise does not match the code | Same suite across sarvam-105b / 30b: accuracy vs latency vs cost | Possible within Sarvam only |
| **Call chaining** (understand → select → instruct) | No | Currently one call. Splitting triples latency against a 700 ms budget | Failures traceable to *conflating* the three jobs | Rejected until evidence |
| **Parallelization** | No | The planner call is a single dependency of the highlight; nothing independent to overlap. TTS already starts after bounds resolve | Latency profile showing a real serial bottleneck | Rejected |
| **RAG** | No | The planner needs the **current screen**, not a corpus. There is no external knowledge to retrieve | A task needing domain knowledge beyond the task DSL | Rejected |
| **Chunking** | No | Screens produce ~10–120 elements, far inside a 64K context | Snapshots overflowing context | Rejected |
| **Agentic loop** | Partially present | `planOpenEnded()` already does autonomous multi-action work (`launch_app`, `click`, `type_text`) and is **completely unevaluated** — no dataset covers it | An open-ended dataset with per-action safety expectations | **Evaluate what already exists before extending it** |
| **Router** | No | One task type, one model | Divergent per-task requirements | Rejected |
| **Fine-tuning** | No | Prompt-level improvements are untried; no labelled corpus; the model is not the current bottleneck — missing guards are | Prompt + guard work exhausted, failures still model-shaped | Rejected |

## The honest summary

The three confirmed critical/major gaps (`FAILURES.md` GAP-1..3) are **all
fixable with deterministic code**. None needs a better model, a bigger context,
retrieval, or an agent.

The single most under-tested surface is `planOpenEnded()`: it can launch apps,
tap, and type on the user's real device, and no eval touches it. That is a
larger risk than anything on the optimization ladder.
