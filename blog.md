# The Model Proposes, the Engines Dispose

LLMs are great at sounding right and bad at being checked. Ask one to solve a math puzzle and you get a confident wall of prose — some of it true, some of it plausible, none of it load-bearing. The usual fix is to prompt harder. [veriframe](https://github.com/yogthos/veriframe) takes the opposite route: it treats the model like code and wraps it in a harness.

The one-line version: veriframe is an OpenAI-compatible HTTP server that takes a problem, runs the model through a verification loop, and refuses to return an answer until every claim in it has been confirmed by a formal engine — Prolog, Z3, Lean, or Octave — *and* the harness has checked that what the engine confirmed is actually what the answer claims. Nothing the model asserts ships unless an engine earned it. The server is written in Jolt, a Clojure dialect on Chez Scheme, and it's a deliberate port rather than a transliteration: the verification architecture was kept, and the control layer was rebuilt around a catalog of ways models fake progress.

## Four engines, four kinds of "yes"

The model has four ways to check a claim, and picking the right one is part of the work:

- **SWI-Prolog with `clpfd`** — for finite search spaces: puzzles, scheduling, enumeration over a bounded range. A goal that succeeds over an exhausted finite domain is a real result.
- **Z3** — for arithmetic and theory-rich claims: unbounded integers, quantifiers, bit-level reasoning. It's also how you prove impossibility: assert the negation, get `unsat`.
- **Lean 4 + Mathlib** — for statements about all naturals, real analysis, algebra. The system prompt makes the point bluntly: testing a thousand instances proves nothing about "all n", and both Prolog and Z3 will cheerfully confirm the instances you handed them rather than the statement you meant. Induction belongs to Lean.
- **GNU Octave** — for numerical work the others can't touch: linear algebra, condition numbers, optimization over reals. And here's the crucial epistemic caveat: the other three *decide* — they exhaust a domain, decide a theory, or check a proof. Octave *computes*, in floating point, so it can refute a universal claim with a counterexample but can never establish one. A result carries the tolerance it was established at and says whether the arithmetic was exact.

Two engines agreeing on a result is worth more than either alone — that's what the `review` tool is for. Two engines disagreeing is a finding, not a nuisance, and the model is told not to paper over it.

## The loop is a coding harness

The loop is a beam search. Five branches attack the problem in parallel, each with its own Prolog session, Lean environment, message history, and turn log. The one thing they share is a failure log — and that sharing is the point. An approach one branch disproved is not retried by another. The log is FTS5-ranked, so a branch sees the failures most similar to what it just tried, not the whole history.

A branch that fails three consecutive verifications is culled — unless it produced a confirmed artifact recently, because a branch that's producing is allowed to stumble. `branch_theses` lets a branch fork into siblings, capped at fifteen. The first branch to land a verified `done` ends the run for everyone.

Shipping is gated like a CI pipeline. The sequence is `thesis` → a `verify_*` that confirms → `review` (or `verify_template`, whose cross-check is baked in) → `audit` → `done`. `done` is refused unless the most recent audit passed against the exact answer being shipped, something was independently cross-checked, and every substantive claim in the answer appears in an artifact an engine confirmed. Those checks are mechanical — no model in the path.

One portability choice stands out: no provider-native tool calling. The model emits a fenced ` ```tool-call ` block containing JSON, and the harness parses it with a repair pass for unescaped control characters. It therefore never has to care whether a provider implements tool calling correctly, and the same server can point at DeepSeek, GLM, OpenAI, or a local `llama-server`.

## The dirty tricks it defends against

The most interesting parts are the edges — all the ways a model can look like it's making progress without proving anything:

**SAT over free variables is not a witness.** Z3 can return SAT without pinning the values: you've proved a solution *exists*, but you don't have one. Such results land in an "existential" bucket that cannot substantiate a concrete answer — same for a Prolog goal that succeeds leaving its variables unbound.

**A confirmation is not progress.** An engine saying yes to "clpfd is available" is not progress on a puzzle. A guard that credits every confirmation cannot see a branch verifying its own tooling, so progress is defined mechanically: a new confirmed artifact, a discharged sub-claim, or a proof-goal count that went down.

**Self-verification doesn't count.** A `verify` whose Prolog goal succeeds only because of a rule the same branch added in the same turn, without independent grounding, does not confirm anything — the analogue of refusing to let an agent-authored check script latch green.

**You can't take back what you proved.** `retract_rule` refuses to remove a named rule that a confirmed artifact depends on. (dirge's version blocked discarding but never modifying; the same split applies here.)

**Verdicts fail closed.** `audit` and `review` both ask a sub-LLM for a verdict, and parsing free text is where the project that shaped this one got burned: its `parse_verdict` once read COMPLETE out of "NOT COMPLETE" because one string was a substring of the other — and got eleven of twenty-seven real judge phrasings wrong, every one in the same direction. veriframe parses against an answer set built so no answer is a substring of another, and ambiguity returns `:unparseable` rather than a guess. An unparseable verdict fails closed: `done` stays blocked.

## One steer per boundary

Gates fire at the end of every branch turn — `done` blocked by a failing audit, safe-state abort, emergency review, milestone, stuck hint after three unproductive verifications, a prologue bound for branches that produced nothing at all, tier escalation when only fast checks ran, turn-budget notices at 60% and 85%. Several can hold at once, but an arbiter emits exactly one message, in strict priority, and records what it outranked. A human directive sits above every machine gate: you can message a branch mid-run and it applies at that branch's next turn boundary.

Every gate declares a prediction when it fires — "this branch calls `review` within two turns" — and a later turn settles it from the journal with no model in the path. That's what makes "does this gate earn its place" answerable with data rather than vibes. It's also how the harness surfaced the honest finding that its stuck hint fires and is obeyed zero times. Real finding, not a bug.

## The proof that it works

A harness is a toy if it never produces anything. The best artifact in the repo is a worked attack on the **Erdős–Selfridge odd covering problem**, open since the 1950s. Selfridge offered $2000 for a construction of a covering system of the integers whose moduli are all odd and distinct; Erdős offered $25 for a proof that none exists. Every covering system ever found has a modulus divisible by 2 or 3.

veriframe runs against `deepseek-v4-flash` produced engine-verified results on it. The headline: the modulus set {3,5,7,9,11,13,15} is the minimal density-feasible candidate — its reciprocal sum is ≈1.0218 ≥ 1, so density alone can't rule it out — yet no choice of residues covers all integers. The maximum coverable is exactly 32805 of the 45045 residue classes, leaving 12240 uncovered in the best case, with an explicit maximizing witness. The proof factorizes the moduli into an entangled part {3,5,9,15} and a coprime part {7,11,13}, then combines an exhaustive search over the entangled part with a CRT independence argument for the coprime part. Every claim was confirmed in at least two engines with independently shaped encodings, and each one sits in the run journal with the exact engine code that verified it.

That's the property worth underlining: not that a model said something smart, but that a model produced something *checkable*, and the checking is re-runnable from the journal by anyone.

## Runs you can watch, crash, and steer

Everything is appended to SQLite as it happens — every turn, tool result, artifact, and gate firing — so a crashed or aborted run stays fully inspectable, and the read API serves a live run and a finished one with the same query. You can tail a run's journal by cursor, post an intervention to a branch, or abort it. A supervisor owns every engine subprocess and can kill them all regardless of what any branch believes — the out-of-band stop that the DS1 remote-agent report made a hard requirement. And sending `"raw": true` bypasses the loop entirely and forwards straight to the provider, which is the control arm every harness should have: you can always compare harnessed against unharnessed.

The design motto, inherited from the dirge work that shaped the control layer, is that prose loses to mechanism. Anything that can be a gate, a tool precondition, or a mechanical check should be one — because mechanisms that make an artifact prove what it claims are worth more than mechanisms that make the model try harder. veriframe is that bet, in production shape: an OpenAI-compatible endpoint that will happily answer your prompt — after it proves the answer.
