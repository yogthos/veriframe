# Lonely Runner Conjecture for $n = 8$ — Lean Scaffolding Run

## Summary

First run on the Lonely Runner Conjecture (LRC) — a pivot away from
the Erdős–Straus problem family. Same harness (thesis gate +
audit Checks A–E), same model (`deepseek-reasoner`), but now with
Lean operational (the prior `spawn lake ENOENT` issue was fixed
by prepending `~/.elan/bin` to the server's PATH).

The result: **a complete Lean 4 / Mathlib formalization of the
LRC scaffolding for $n = 8$**, with verified definitions, four
verified lemmas, and the WLOG-zero reduction theorem — all
proofs compiled (no `sorry`).

This hits **Target B** of the problem prompt exactly:
"Lean-formalize the conjecture statement and the reduction
structure."

## What got shipped (B2's final answer, audit-passed)

### Definitions

```lean
import Mathlib

def isLonelyAt (S : Finset ℤ) (n : ℕ) (t : ℝ) : Prop :=
  ∀ v ∈ S, (1 : ℝ) / n ≤ Int.fract (v * t) ∧
            Int.fract (v * t) ≤ 1 - (1 : ℝ) / n

def LonelyRunnerHolds (S : Finset ℤ) (n : ℕ) : Prop :=
  ∃ t : ℝ, t ∈ Set.Ico (0 : ℝ) 1 ∧ isLonelyAt S n t

def LonelyRunnerConjecture (n : ℕ) : Prop :=
  ∀ S : Finset ℤ, S.card = n - 1 → (0 ∉ S) →
    LonelyRunnerHolds S n

def LonelyRunnerConjecture8 : Prop := LonelyRunnerConjecture 8
```

### Verified lemmas

1. **`fract_iff_circledist`** — circular distance vs fractional-part
   condition equivalence:
   ```lean
   theorem fract_iff_circledist (x : ℝ) (n : ℕ) (hnpos : 0 < n) :
     (min x (1 - x) ≥ (1 : ℝ) / n) ↔
     ((1 : ℝ) / n ≤ x ∧ x ≤ 1 - (1 : ℝ) / n)
   ```

2. **`fract_mul_add_int`** — integer time-shift invariance:
   ```lean
   lemma fract_mul_add_int (v : ℤ) (t : ℝ) (k : ℤ) :
     Int.fract (v * (t + (k : ℝ))) = Int.fract (v * t)
   ```

3. **`isLonelyAt_add_int`** — applies time-shift invariance to the
   lonely property.

4. **`wlog_zero_finset`** — generic Finset-level WLOG-zero
   correspondence:
   ```lean
   lemma wlog_zero_finset (S : Finset ℤ) (i : ℤ) (P : ℤ → ℝ → Prop) (t : ℝ) :
     (∀ v ∈ S, v ≠ i → P (v - i) t) ↔
     (∀ u ∈ Finset.image (fun x : ℤ => x - i) S, u ≠ (0 : ℤ) → P u t)
   ```

### The WLOG-zero reduction theorem

```lean
theorem wlog_zero_reduction (T : Finset ℤ) (j : ℤ) (n : ℕ)
    (hnpos : 0 < n) (t : ℝ) :
  (∀ v ∈ T, v ≠ j → min (Int.fract ((v - j : ℤ) * t))
                        (1 - Int.fract ((v - j : ℤ) * t))
                        ≥ (1 : ℝ) / (n : ℝ))
  ↔
  (∀ u ∈ Finset.image (fun (x : ℤ) => x - j) T, u ≠ (0 : ℤ) →
    ((1 : ℝ)/(n : ℝ) ≤ Int.fract (u * t) ∧
     Int.fract (u * t) ≤ 1 - (1 : ℝ)/(n : ℝ)))
```

This is the formal bridge between the original
$n$-runners-with-distinct-speeds formulation and the
$(n-1)$-runners-with-WLOG-zero-runner formulation that all the
literature uses.

## How the harness behaved

The lake fix unlocked a qualitatively different operating mode.
Compare tool histograms across recent runs (steps × tool count):

| Tool | Erdős mod-12 (115) | Erdős mod-28 (137) | Erdős ED2 reduce (205) | LRC (276) |
|---|---:|---:|---:|---:|
| `__no_call__` | 56 (49%) | 92 (67%) | 123 (60%) | **13 (4.7%)** |
| `verify_smt` | 25 | 17 | 14 | 11 |
| `lean_define` | 2 | 2 | 13 | **53** |
| `proof_step` | 0 | 0 | 11 | **72** |
| `proof_start` | 0 | 0 | 4 | **27** |
| `proof_close` | 0 | 0 | 0 | **20** |
| `verify_lean` | 1 | 0 | 4 | **17** |
| `lean_search` | 4 | 0 | 3 | **18** |

The model went from "guessing universal-quantified Z3 queries
with high `__no_call__` overhead" to "stepwise Lean proof work
with sub-5% `__no_call__`." This is the harness operating as
designed — Lean is the right tool for genuine universal claims,
and once it's available, the model uses it.

195 Lean operations dominated this run. The thesis-gate +
audit-gate stack remained active (7 thesis calls, 5 audit calls,
2 `done` attempts with the first blocked by substantiation
check); the model iterated on framing until B2 shipped a
honestly-scoped Mathlib-grade scaffolding artifact.

## Beam dynamics

| Branch | Turns | Status | Notes |
|---|---|---|---|
| B1 | 65 | culled | 11 artifacts; ambitious approach, didn't ship |
| **B2** | **88** | **DONE** | **Target B: formalization scaffolding, shipped** |
| B3 | 28 | culled | early dead end |
| B4 | 7 | culled | very early dead end |
| B5 | 88 | abandoned | superseded by B2 |

B1 was the most active branch by artifact count but was culled
after consecutive failures — it had attempted Target A (a verified
universal gap-of-loneliness lower bound) but the model couldn't
land a proof in the budget. B2 chose Target B (formalization)
which was tractable in the budget, and executed cleanly.

## Honest novelty assessment

**Mathematically novel?** No — LRC's standard reductions
(WLOG-zero, integer time-shift invariance) are textbook. None
of the proven lemmas constitute a new mathematical result.

**Formally novel?** Probably yes. Mathlib does not currently
have LRC formalized (search reveals no `LonelyRunner*` declarations
in Mathlib's index). The four lemmas above plus the WLOG-zero
reduction are a clean, self-contained scaffolding that could be
the foundation of a Mathlib contribution.

**Strategically meaningful?** Yes. This scaffolding is the prerequisite for any future formal-verification work on LRC. With these definitions and reductions in place, attacking Targets A / C / D / E becomes a matter of building on top — the boilerplate is done.

**TODO — potential Mathlib contribution.** The scaffolding shipped here (definitions of `isLonelyAt`, `LonelyRunnerHolds`, `LonelyRunnerConjecture`, the `fract_iff_circledist` and `fract_mul_add_int` lemmas, and the `wlog_zero_reduction` theorem) is a candidate for upstream submission to Mathlib. Steps:

1. Refactor names to Mathlib conventions (e.g., `LonelyRunner.IsLonelyAt`, `LonelyRunner.Conjecture`).
2. Move to a dedicated file (e.g., `Mathlib/NumberTheory/LonelyRunner/Basic.lean`).
3. Add the proofs of the $n \leq 7$ cases as `have` references citing literature (Wills 1967 etc.) or as `axiom`s pending future formalization.
4. Add docstrings + TeX-rendered conjecture statement.
5. Submit PR.

## Reproduction

Run on commit
[`f034edf`](https://github.com/yogthos/veriframe/commit/f034edf)
of `main`, problem `lonely-runner-n8`, max 100 turns,
`HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`. Trace at
`/tmp/agent-lonely-runner-n8.json`. All Lean proofs re-check
under any modern Lean 4 + Mathlib (≥ 4.10).

**Server launch requirement**: `PATH="$HOME/.elan/bin:$PATH"` must
be in the server's environment, otherwise `lake` is not found and
the Lean REPL fails with `spawn ENOENT`. The server-launch
incantation is:
```
PATH="$HOME/.elan/bin:$PATH" HARNESS_PROVIDER=deepseek HARNESS_PORT=3001 npm start
```

## What this run unlocks

The shipped scaffolding is the **foundation** for tackling the
mathematically harder targets on LRC:

- **Target A (universal gap-of-loneliness lower bound for $n=8$)**:
  with `isLonelyAt` and the WLOG-zero reduction in scope, the model
  can now state and attack $\nu(S) \geq c$ over symbolic speed sets
  in Lean directly. The next run should target this.
- **Target C (verified structural reduction)**: with the
  WLOG-zero reduction in scope, narrowing the open class of speed
  configurations is now a matter of writing one more reduction
  theorem.
- **Target D (verified sub-class result)**: arithmetic-progression
  speeds, geometric-progression speeds, etc. — all directly
  expressible as restricted instances of `LonelyRunnerConjecture8`.
- **Target E (verified obstruction)**: building on the scaffolding,
  formalize "no proof of method $X$ closes $n = 8$" arguments.

Next planned run: **Target A** — attempt to verify a universal
lower bound on $\nu(S)$ for $n = 8$ that improves on Chen–Cusick's
$1/(2n-3) = 1/13$.

## What this run says about the harness

The `__no_call__` rate dropping from ~60% to <5% is the strongest
signal yet that the harness's defense stack works **provided the
right tools are available**. With Lean operational, the model
defaults to stepwise proof work, which is honest and verifiable
by construction. With only Z3 over symbolic variables, the model
defaults to algebraic-identity verification, which we now know
tends to slip into re-derivations of published identities (caught
by Check E only sometimes).

**Implication for problem selection**: problems whose universal
claims are accessible via Lean stepwise proofs (induction over
$n$, structural reduction lemmas, definitional unfolding) are a
better fit for the harness than problems whose universal claims
are accessible only through analytic / sieve-theoretic methods
that require infrastructure Lean's Mathlib doesn't yet have.
The Erdős–Straus residual was the latter; LRC scaffolding is
the former.
