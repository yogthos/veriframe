# Hadwiger–Nelson, Moser Spindle Embedding — Lean 9-of-11 Edge Verification

## Summary

Run on `hadwiger-nelson-moser-embedding` (commit `54d7af7`). The
chromatic-number lower bound for the abstract Moser spindle was
pre-staged as a verified prelude (verified by hand against
Mathlib before the run started). The model's task: define the
unit-distance embedding $f : \text{Fin 7} \to \mathbb{R}^2$,
prove all 11 edge-distance equations, and combine into the final
$\chi(\mathbb{R}^2) \geq 4$ theorem.

**Result: 9 of 11 edges Lean-verified. No final ship — audit
gate blocked all 4 `done` attempts.**

This is the most engaged the harness has ever been: 456 steps,
~365 Lean operations, `__no_call__` rate 5.5% (record-low).

## What got verified

### Pre-staged scaffolding (verified by hand before the run)

The full chromatic-number proof chain compiles against Mathlib:

```lean
def spindleAdj : Fin 7 → Fin 7 → Bool := …  -- 11 edges
def isProperColoring {k : ℕ} (f : Fin 7 → Fin k) : Bool := …
theorem no_3_coloring : ¬ ∃ f : Fin 7 → Fin 3, isProperColoring f = true
  := by native_decide
theorem exists_4_coloring : ∃ f : Fin 7 → Fin 4, isProperColoring f = true
  := by native_decide
def moserSpindle : SimpleGraph (Fin 7) := …
theorem moserSpindle_not_colorable_3 : ¬ moserSpindle.Colorable 3 := …
theorem moserSpindle_chromaticNumber_ge_4 : 4 ≤ moserSpindle.chromaticNumber := …
```

Reference Lean file: `/tmp/spindle_color_test9.lean` (compiles
under Lean 4.29.1 + Mathlib).

### Edge unit-distance proofs the run produced

Standard coordinates with $\cos\theta = 5/6$, $\sin\theta = \sqrt{11}/6$:

| $i$ | $f(i) = (x_i, y_i)$ |
|---|---|
| 0 | $(0, 0)$ |
| 1 | $(1, 0)$ |
| 2 | $(1/2, \sqrt 3/2)$ |
| 3 | $(3/2, \sqrt 3/2)$ |
| 4 | $(5/6, \sqrt{11}/6)$ |
| 5 | $((5 - \sqrt{33})/12,\ (\sqrt{11} + 5\sqrt 3)/12)$ |
| 6 | $((15 - \sqrt{33})/12,\ (3\sqrt{11} + 5\sqrt 3)/12)$ |

| Edge | Status | Tactic |
|---|---|---|
| $\{0, 1\}$ | ✓ confirmed | `ring` / `norm_num` |
| $\{0, 2\}$ | ✓ confirmed | `nlinarith` with $\sqrt 3$ |
| $\{1, 2\}$ | ✓ confirmed | `nlinarith` with $\sqrt 3$ |
| $\{1, 3\}$ | ✓ confirmed | `nlinarith` with $\sqrt 3$ |
| $\{2, 3\}$ | ✓ confirmed | `ring` |
| $\{0, 4\}$ | ✓ confirmed | `nlinarith` with $\sqrt{11}$ |
| $\{0, 5\}$ | ✓ confirmed | `nlinarith` with $\sqrt 3, \sqrt{11}, \sqrt{33}$ |
| $\{4, 5\}$ | ✓ confirmed | `nlinarith` with sqrt lemmas |
| $\{4, 6\}$ | ✓ confirmed | `nlinarith` with sqrt lemmas |
| $\{5, 6\}$ | ✗ refuted | `nlinarith` couldn't close the polynomial |
| $\{3, 6\}$ | ✗ refuted | `nlinarith` couldn't close the polynomial |

The model used `Real.mul_self_sqrt` and the multiplicative
identity $\sqrt 3 \cdot \sqrt{11} = \sqrt{33}$ as side hypotheses.
Plus an SMT cross-check artifact:
**"All 11 Moser spindle edges have unit length under the explicit
$f$"** — Z3 confirmed numerically.

The two failing edges $\{5,6\}$ and $\{3,6\}$ are mathematically
unit-distance (verified by hand below) — `nlinarith` just runs
out of room on the expanded polynomial expressions.

### Hand-verification of the missing edges

**Edge $\{5, 6\}$:**
$\Delta x = (5-\sqrt{33})/12 - (15-\sqrt{33})/12 = -10/12 = -5/6$.
$\Delta y = (\sqrt{11}+5\sqrt 3)/12 - (3\sqrt{11}+5\sqrt 3)/12 = -2\sqrt{11}/12 = -\sqrt{11}/6$.
$\Delta x^2 + \Delta y^2 = 25/36 + 11/36 = 1$. ✓

(Notably: this reduces to the same form as edge $\{0, 4\}$ which
**did** verify. The model didn't simplify before attempting
`nlinarith` and the unsimplified polynomial overwhelmed the tactic.)

**Edge $\{3, 6\}$:**
$\Delta x = 3/2 - (15-\sqrt{33})/12 = (3 + \sqrt{33})/12$.
$\Delta y = \sqrt 3/2 - (3\sqrt{11}+5\sqrt 3)/12 = (\sqrt 3 - 3\sqrt{11})/12$.
$\Delta x^2 + \Delta y^2 = ((3 + \sqrt{33})^2 + (\sqrt 3 - 3\sqrt{11})^2)/144$.
$(3+\sqrt{33})^2 = 9 + 6\sqrt{33} + 33 = 42 + 6\sqrt{33}$.
$(\sqrt 3 - 3\sqrt{11})^2 = 3 - 6\sqrt{33} + 99 = 102 - 6\sqrt{33}$.
Sum: $144$. Divided by $144 = 1$. ✓

Both missing edges are tractable with a `ring`-based
pre-simplification of $\Delta x, \Delta y$ before the
`nlinarith` step. This is a fixable proof-strategy issue, not a
mathematical obstruction.

## Why no `done` shipped — the audit gate's behavior

4 `done` attempts, 10 `audit` calls, all failed. The audit's
Check A (encoding soundness) reasoning across attempts:

1. *"Encoding soundness cannot be evaluated because the actual
   Lean code for f, the edge lemmas, planeUnitDistanceGraph is
   not provided; the presented artifact is a trivial restatement
   that depends on undefined symbols."*
2. *"The Lean snippet contains `import Mathlib`, which the
   problem explicitly forbids (REPL auto-imports); this would
   cause a compilation error, so the encoding is unsound."*
3. *"The model claims all 11 edges have been verified by Lean,
   but only a single edge (5–6) is provided as code; the
   remaining 10 are referenced by name."*

The audit was **technically correct** that the proposed answer
was under-specified — the model kept submitting verbal summaries
("all 11 edges verified" + a list of theorem names) rather than
the actual Lean code. But the audit also got confused by
`import Mathlib` appearing in code excerpts (the REPL auto-imports;
the excerpts were just for documentation), conflating excerpt
text with what was actually executed.

Net: **a real near-result blocked by audit-gate framing**.
The harness's verification engines (Lean kernel) accepted 9
edges; the audit's LLM-driven Check A rejected the framing.

## Tool histogram

456 total steps:
- 113 `lean_define` — model defining and re-defining structures
- 83 `proof_step` — stepwise tactic proofs
- 62 `proof_start` — opening proof sessions
- 45 `verify_lean` — one-shot proof attempts
- 35 `proof_abandon` — backing out of failed attempts
- 27 `proof_close` — completing proofs
- 25 `__no_call__` (5.5% — record-low engagement-per-turn)
- 21 `lean_search` — looking up Mathlib lemmas
- 16 `proof_state` — checking goal state
- 10 `audit`, 5 `review`, 5 `thesis`, 4 `done` (all blocked)

365 Lean operations dominated. The lake-fix pays off here —
once Lean is operational the model uses it heavily.

## What this run says about the harness

**Strengths the run demonstrated:**
- The thesis-first protocol kept the model focused (5 thesis calls,
  steady refinement).
- The pre-staged verified prelude let the model skip the
  combinatorics machinery hurdle and focus on the geometry.
- Lean stepwise proof tools were used heavily and effectively.
- The cross-check infrastructure (SMT review of the embedding)
  worked — Z3 numerically confirmed all 11 distances.

**Weaknesses the run exposed:**
- **Audit Check A is over-strict on Lean snippet excerpts**:
  it can't distinguish between "the Lean code that was actually
  executed (no import Mathlib needed, REPL handles it)" and
  "the Lean code shown in the proposed answer for documentation"
  with `import Mathlib` for clarity. The audit should have
  context that the harness's REPL auto-imports.
- **The `proposedAnswer` requirement isn't well-specified**:
  the model didn't know whether to include verbatim Lean code
  for all 11 edges or summarize.
- **`nlinarith` polynomial complexity ceiling** is reached on
  the inter-rhombus edges of the Moser spindle. Manual
  pre-simplification (`ring`-rewriting before `nlinarith`)
  would unblock — the model didn't try this strategy.

## Honest novelty assessment

**Mathematically novel?** No. The Moser spindle's
unit-distance embedding is textbook (Moser & Moser 1961). The
chromatic-number-of-the-plane bound $\chi(\mathbb{R}^2) \geq 4$
is the canonical pre-de-Grey result.

**Formally novel?** Probably yes, modestly. Mathlib does not
currently have a Hadwiger–Nelson formalization. The 9 verified
edge unit-distance lemmas plus the chromatic-number proof are
the bulk of a Mathlib contribution; the missing 2 edges are a
fixable proof-tactic issue, not a structural blocker.

**Strategically meaningful?** Yes — this is the foundation
for any future Hadwiger–Nelson work in Mathlib (the de-Grey
$\chi \geq 5$ result, Hadwiger's $\chi \leq 7$ upper bound,
sub-residue chromatic-class reductions).

## TODO — Mathlib contribution

The shipped scaffolding (chromatic-lower-bound + 9 edge
unit-distance lemmas) is a candidate for upstream submission
to Mathlib once the missing 2 edges are completed:

1. Complete edges $\{5,6\}$ and $\{3,6\}$ via `ring`-rewriting
   pre-simplification (the unsimplified expressions overwhelm
   `nlinarith`).
2. Combine into a clean `MoserSpindle.chiR2_ge_4` theorem.
3. Refactor names to Mathlib conventions
   (`MathLib/Combinatorics/SimpleGraph/HadwigerNelson/MoserSpindle.lean`).
4. Add docstrings + literature references.
5. Submit PR.

## Reproduction

Run on commit
[`54d7af7`](https://github.com/yogthos/veriframe/commit/54d7af7)
of `main`, problem `hadwiger-nelson-moser-embedding`,
max 100 turns, `HARNESS_PROVIDER=deepseek` with
`deepseek-reasoner`. Trace at
`/tmp/agent-hadwiger-nelson-moser-embedding.json`. The 9
confirmed-status Lean artifacts re-check under Lean 4.29.1 +
Mathlib.

**Server launch requirement** (recurring):
`PATH="$HOME/.elan/bin:$PATH"` must be set, otherwise the Lean
REPL fails with `spawn lake ENOENT`.

## What unlocks next

**Direct follow-up**: pre-stage the 9 verified edge lemmas as
the new prelude and ask the model to:
1. Prove the missing edges $\{5,6\}$ and $\{3,6\}$ (via
   `ring`-pre-simplification, hint provided).
2. Define the homomorphism `fHom : moserSpindle →g planeUnitDistanceGraph`.
3. Combine into the final `chiR2_ge_4` theorem.

This is a much narrower task (2 edges + homomorphism + final
theorem, ~50 turns of work) and avoids the audit-gate-confusion
issue by having the proposedAnswer reference an explicit
already-verified prelude.

If THAT fails too, the alternate path is: encode the unit-distance
embedding + the 4-coloring impossibility entirely in Z3, ship a
SAT-verified chromatic lower bound for an explicit graph in
$\mathbb{R}^2$, and accept that the Lean formalization may need
the missing pieces filled in by hand.
