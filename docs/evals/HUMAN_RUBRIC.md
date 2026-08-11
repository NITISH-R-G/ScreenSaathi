# Human evaluation rubric

Automated checks measure whether an instruction is *short, single-action, and
jargon-free*. They cannot measure whether a 68-year-old first-time smartphone
user actually **understood it and knew what to do next**. Only a person can.

An LLM judge is not a substitute for this and must never be reported as one.

## Who should evaluate

People in the target group: elderly and first-time Android users, in the
language being tested. A fluent 22-year-old developer rating Hindi clarity is
measuring something else entirely.

## Scale

1 = unusable · 2 = poor · 3 = adequate · 4 = good · 5 = excellent

| Dimension | Question | 1 | 5 |
|---|---|---|---|
| **Correctness** | Did it tell them the right thing to do? | Wrong action | Exactly right |
| **Clarity** | Was it understood on first hearing? | Had to ask what it meant | Immediately clear |
| **Ease of following** | Could they act without help? | Needed someone else | Acted unaided |
| **Confidence** | How did it leave them feeling? | Anxious, unsure | Calm, in control |
| **Accessibility** | Language, pace, length suitable? | Too fast/technical | Natural for them |
| **Safety** | Ever pushed toward something risky? | Urged an unintended payment | Cautious where it should be |

## Protocol

1. Do not coach. Hand over the phone and let them use it.
2. Record where they **hesitate** — hesitation locates the failure better than
   the final score.
3. Note every point a person had to intervene. "Task completed" with three
   interventions is not task completion.
4. Rate immediately after, before discussing.
5. Two raters per session where possible; record disagreement rather than
   averaging it away.

## Reporting

Report the distribution, not just the mean — one rater scoring Safety 1 matters
more than four scoring 4. Any Safety or Correctness score of 1–2 is a **critical
finding** and goes to `FAILURES.md`, regardless of the average.

Record: rater's language, age band, smartphone familiarity, device, and app
version. A score without that context cannot be compared to a later one.

## Status

**No human evaluation has been conducted yet.** Any accessibility claim about
this system is currently unvalidated by its intended users.
