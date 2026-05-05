# Hadwiger–Nelson, $\chi(\mathbb{R}^2) \geq 4$ via Moser Spindle — Full Lean Ship

## Summary

After four iterations on the Hadwiger–Nelson problem, the harness
shipped a **complete, audit-passed, end-to-end Lean 4 / Mathlib
formalization of $\chi(\mathbb{R}^2) \geq 4$ via the Moser spindle**
(`hadwiger-nelson-moser-final`, commit `be03102`). This is the first
fully-shipping run on this problem family.

The mathematics is textbook (Moser & Moser, 1961). The harness's
contribution is the **machine-verified Lean formalization**, which
is currently absent from Mathlib and is a candidate for upstream
submission.

## Timeline of all four runs

| Run | Commit | Steps | Status | Outcome |
|---|---|---|---|---|
| `hadwiger-nelson-chi` | `86db656` | 45 | beam exhausted | 0 verifications; coordinate-first attempt got stuck on `Real.sqrt` in pair literals; `import Mathlib` mid-stream issues |
| `hadwiger-nelson-moser-lean` | `88450b0` | 31 | beam exhausted | 0 verifications; same failure mode; `SimpleGraph.Coloring` decidability obstruction |
| `hadwiger-nelson-moser-embedding` | `54d7af7` | 456 | beam exhausted | **9 of 11 edges verified**; final theorem assembly built but every audit attempt blocked on framing |
| **`hadwiger-nelson-moser-final`** | **`be03102`** | **213** | **DONE** | **Full ship — `chiR2_ge_4` audit-passed and finalized** |

## What enabled the ship (third → fourth run)

The prior 9-of-11 result + 4 specific framing fixes:

1. **Hand-verified the missing 2 edges** ($\{5,6\}$ and $\{3,6\}$). The model's attempts in run 3 used straight `nlinarith` and exhausted the polynomial-arithmetic ceiling. The fix: **`ring`-rewrite of $\Delta x, \Delta y$ before `nlinarith`** with the sqrt lemmas in scope. Both edges then close cleanly.
2. **Hand-verified the homomorphism + final theorem assembly** end-to-end (`docs/lean-artifacts-MoserSpindle.lean`).
3. **Pre-staged the entire verified pipeline in 6 chunks** so the model only needs to load + verify + ship.
4. **Made the audit-framing rules explicit**: the `proposedAnswer` MUST include the verbatim theorem signature, MUST NOT include `import Mathlib`, MUST be honestly scoped to $\chi \geq 4$ (pre-de-Grey).

## Beam dynamics in the final run

| Branch | Turns | Status | Notes |
|---|---|---|---|
| B1 | 52 | abandoned | 5 artifacts; tried but didn't land audit-passing framing |
| B2 | 52 | abandoned | 13 artifacts; tried to ship at step 94, blocked on substantiation; superseded |
| **B3** | **52** | **DONE** | **9 artifacts; landed at step 155 with `(review: passed) (audit: passed)`** |
| B4 | 52 | abandoned | 10 artifacts; was still iterating when B3 won |
| B5 | 5 | culled | early dead end |

B3's path: 5 thesis sub-claims, each verified via `proof_start + proof_step` in the persistent Lean REPL, three audit attempts blocked on substantiation token-check (`proposedAnswer` had to be refined to include enough distinctive identifiers from the verified artifacts), audit pass at step 154, ship at step 155.

## Tool histogram (213 steps)

- 39 `proof_start`, 29 `proof_step`, 6 `proof_close`, 8 `proof_abandon`, 7 `proof_state`
- 31 `verify_lean`, 30 `lean_define`
- 16 `audit`, 9 `review`, 6 `thesis`, 4 `done` (1 success, 3 blocked)
- 10 `verify_smt` — including a Z3 confirmation that the abstract spindle is not 3-colorable
- 15 `__no_call__` (7%) — strong tool-call discipline

The Lean operations dominate: 150 of 213 steps were Lean tactic / definition operations.

## The complete Lean proof (audit-passed, end-to-end verified)

The full source is at `docs/lean-artifacts-MoserSpindle.lean`.
Compiles against Lean 4.29.1 + Mathlib with no warnings.

### Definitions

```lean
import Mathlib

-- Boolean adjacency: 11 edges, two rhombi (vertices {0,1,2,3} and {0,4,5,6})
-- joined at vertex 0 with rhombus 2 rotated, plus connecting edge {3, 6}.
def spindleAdj : Fin 7 → Fin 7 → Bool
  | 0, 1 => true | 1, 0 => true | 0, 2 => true | 2, 0 => true
  | 1, 2 => true | 2, 1 => true | 1, 3 => true | 3, 1 => true
  | 2, 3 => true | 3, 2 => true | 0, 4 => true | 4, 0 => true
  | 0, 5 => true | 5, 0 => true | 4, 5 => true | 5, 4 => true
  | 4, 6 => true | 6, 4 => true | 5, 6 => true | 6, 5 => true
  | 3, 6 => true | 6, 3 => true | _, _ => false

def isProperColoring {k : ℕ} (f : Fin 7 → Fin k) : Bool :=
  decide (∀ i j : Fin 7, spindleAdj i j = true → f i ≠ f j)

def moserSpindle : SimpleGraph (Fin 7) where
  Adj i j := spindleAdj i j = true
  symm := by intro i j h; fin_cases i <;> fin_cases j <;> simp_all [spindleAdj]
  loopless := ⟨by intro i h; fin_cases i <;> simp [spindleAdj] at h⟩

instance : DecidableRel moserSpindle.Adj := fun i j => by
  unfold moserSpindle; exact inferInstance

def planeUnitDistanceGraph : SimpleGraph (ℝ × ℝ) where
  Adj p q := p ≠ q ∧ (p.1 - q.1)^2 + (p.2 - q.2)^2 = 1
  symm := by
    intro p q ⟨hne, hd⟩
    refine ⟨hne.symm, ?_⟩
    have : (q.1 - p.1)^2 + (q.2 - p.2)^2 = (p.1 - q.1)^2 + (p.2 - q.2)^2 := by ring
    rw [this]; exact hd
  loopless := ⟨fun p ⟨hne, _⟩ => hne rfl⟩

-- The Moser spindle's standard embedding into ℝ². The rotation angle θ
-- has cos θ = 5/6 and sin θ = √11 / 6, chosen so that the connecting
-- edge {3, 6} also has unit length.
noncomputable def f : Fin 7 → ℝ × ℝ
  | 0 => (0, 0)
  | 1 => (1, 0)
  | 2 => (1/2, Real.sqrt 3 / 2)
  | 3 => (3/2, Real.sqrt 3 / 2)
  | 4 => (5/6, Real.sqrt 11 / 6)
  | 5 => ((5 - Real.sqrt 33) / 12, (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
  | 6 => ((15 - Real.sqrt 33) / 12, (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
```

### Chromatic part (verified by `native_decide`)

```lean
theorem no_3_coloring : ¬ ∃ f : Fin 7 → Fin 3, isProperColoring f = true := by
  native_decide

theorem moserSpindle_not_colorable_3 : ¬ moserSpindle.Colorable 3 := by
  intro h
  obtain ⟨c⟩ := h
  apply no_3_coloring
  refine ⟨fun i => c i, ?_⟩
  unfold isProperColoring
  simp only [decide_eq_true_eq]
  intro i j hadj
  exact c.valid hadj
```

### The 11 edge unit-distance lemmas

```lean
theorem edge_0_1 : ((f 0).1 - (f 1).1)^2 + ((f 0).2 - (f 1).2)^2 = 1 := by
  show ((0:ℝ) - 1)^2 + ((0:ℝ) - 0)^2 = 1; norm_num

theorem edge_0_2 : ((f 0).1 - (f 2).1)^2 + ((f 0).2 - (f 2).2)^2 = 1 := by
  show ((0:ℝ) - 1/2)^2 + ((0:ℝ) - Real.sqrt 3 / 2)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

-- … edges {1,2}, {1,3}, {2,3}, {0,4} use the same nlinarith pattern …

theorem edge_0_5 : ((f 0).1 - (f 5).1)^2 + ((f 0).2 - (f 5).2)^2 = 1 := by
  show ((0:ℝ) - (5 - Real.sqrt 33) / 12)^2
    + ((0:ℝ) - (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

-- … edges {4,5}, {4,6} use the same all-sqrt-lemmas pattern …

-- The 2 hard edges that prior runs couldn't close: ring-rewrite Δx, Δy first.
theorem edge_5_6 : ((f 5).1 - (f 6).1)^2 + ((f 5).2 - (f 6).2)^2 = 1 := by
  show (((5 : ℝ) - Real.sqrt 33) / 12 - ((15 : ℝ) - Real.sqrt 33) / 12)^2
    + ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12
       - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h_dx : (((5 : ℝ) - Real.sqrt 33) / 12
               - ((15 : ℝ) - Real.sqrt 33) / 12) = -5/6 := by ring
  have h_dy : ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12
               - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
             = -Real.sqrt 11 / 6 := by ring
  rw [h_dx, h_dy]
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  nlinarith [h11]

theorem edge_3_6 : ((f 3).1 - (f 6).1)^2 + ((f 3).2 - (f 6).2)^2 = 1 := by
  show ((3:ℝ)/2 - ((15 : ℝ) - Real.sqrt 33) / 12)^2
    + (Real.sqrt 3 / 2 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h_dx : ((3 : ℝ)/2 - ((15 : ℝ) - Real.sqrt 33) / 12)
            = (3 + Real.sqrt 33) / 12 := by ring
  have h_dy : (Real.sqrt 3 / 2 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
            = (Real.sqrt 3 - 3 * Real.sqrt 11) / 12 := by ring
  rw [h_dx, h_dy]
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]
```

### Combined edge lemma + final theorem

```lean
theorem distSq_symm (i j : Fin 7)
    (h : ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 = 1) :
    ((f j).1 - (f i).1)^2 + ((f j).2 - (f i).2)^2 = 1 := by
  have : ((f j).1 - (f i).1)^2 + ((f j).2 - (f i).2)^2
       = ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 := by ring
  rw [this]; exact h

theorem f_edge_distSq : ∀ i j : Fin 7, spindleAdj i j = true →
    ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 = 1 := by
  intro i j hadj
  fin_cases i <;> fin_cases j <;> simp_all [spindleAdj] <;> first
    | exact edge_0_1 | exact distSq_symm 0 1 edge_0_1
    | exact edge_0_2 | exact distSq_symm 0 2 edge_0_2
    | exact edge_1_2 | exact distSq_symm 1 2 edge_1_2
    | exact edge_1_3 | exact distSq_symm 1 3 edge_1_3
    | exact edge_2_3 | exact distSq_symm 2 3 edge_2_3
    | exact edge_0_4 | exact distSq_symm 0 4 edge_0_4
    | exact edge_0_5 | exact distSq_symm 0 5 edge_0_5
    | exact edge_4_5 | exact distSq_symm 4 5 edge_4_5
    | exact edge_4_6 | exact distSq_symm 4 6 edge_4_6
    | exact edge_5_6 | exact distSq_symm 5 6 edge_5_6
    | exact edge_3_6 | exact distSq_symm 3 6 edge_3_6

theorem chiR2_ge_4 : 4 ≤ planeUnitDistanceGraph.chromaticNumber := by
  have h_not_3 : ¬ planeUnitDistanceGraph.Colorable 3 := by
    intro ⟨c⟩
    apply moserSpindle_not_colorable_3
    refine ⟨{
      toFun := fun i => c (f i),
      map_rel' := by
        intro i j hij
        have hadj : spindleAdj i j = true := hij
        have hd : ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 = 1 :=
          f_edge_distSq i j hadj
        have hne : f i ≠ f j := by
          intro heq
          rw [heq] at hd
          have : ((f j).1 - (f j).1)^2 + ((f j).2 - (f j).2)^2 = 0 := by ring
          linarith
        exact c.valid ⟨hne, hd⟩
    }⟩
  by_contra h
  rw [not_le] at h
  have hle : planeUnitDistanceGraph.chromaticNumber ≤ 3 := by
    have : planeUnitDistanceGraph.chromaticNumber < (3 : ℕ∞) + 1 := h
    exact Order.le_of_lt_add_one this
  apply h_not_3
  rw [show (3 : ℕ∞) = ((3 : ℕ) : ℕ∞) from rfl] at hle
  exact (SimpleGraph.chromaticNumber_le_iff_colorable).mp hle
```

## Honest novelty assessment

**Mathematically novel?** No. The Moser spindle and its embedding
are textbook (Moser & Moser, 1961). The result $\chi(\mathbb{R}^2) \geq 4$
is the canonical pre-de-Grey lower bound.

**Formally novel?** Yes. Mathlib does not currently have any
Hadwiger–Nelson formalization. This is a complete machine-verified
proof of $\chi(\mathbb{R}^2) \geq 4$ — a candidate for upstream
contribution.

**Strategically meaningful?** Yes. This is the foundation for any
future Hadwiger–Nelson work in Mathlib (formalizing de Grey 2018's
$\chi \geq 5$, Hadwiger 1945's $\chi \leq 7$ upper bound, and
sub-residue chromatic-class reductions).

## What this run says about the harness

**Strengths exposed:**
- The four-run progression (each adding precision) is exactly how
  the harness should be used: each run reveals what the next needs.
- The thesis-first protocol kept B3 focused across 52 turns of
  detailed proof work.
- The audit gate's framing-strict behavior, while frustrating in
  run 3, ultimately forced the precise final answer that landed.

**Limitations exposed:**
- The audit's substantiation-token check requires the
  `proposedAnswer` to enumerate enough distinctive identifiers from
  recent verified artifacts — this took 3 audit attempts to satisfy
  for a structurally-correct proof.
- For Lean-heavy proofs, the harness needs **hand-verified prelude
  pre-staging**: the model's `nlinarith`-applications stall on
  certain polynomial expressions, and providing a known-working
  proof recipe upfront is what unlocked the full ship.
- The harness's contribution on this problem was **bundling and
  audit-framing**, not novel mathematics. The mathematics was
  hand-verified before the run; the harness packaged and shipped
  it under audit constraints.

## TODO — Mathlib upstream PR (in progress)

This file is a candidate for upstream submission to Mathlib at
`Mathlib/Combinatorics/SimpleGraph/HadwigerNelson/MoserSpindle.lean`.
Steps to PR:

1. ✅ Verify the proof end-to-end (this run + hand verification).
2. **In progress**: refactor names to Mathlib conventions
   (`MoserSpindle.adj`, `MoserSpindle.graph`, `Plane.unitDistanceGraph`,
   `Plane.chromaticNumber_ge_four`, etc.).
3. Add docstrings + literature references in the file header.
4. Add module-doc comment with the conjecture statement.
5. Match the `import Mathlib.Combinatorics.SimpleGraph.Coloring` /
   `import Mathlib.Analysis.SpecialFunctions.Pow.Real` style minimal
   imports.
6. Prepare PR with explanatory commit message.

(The next harness run will tackle this refactor.)

## Reproduction

Run on commit
[`be03102`](https://github.com/yogthos/veriframe/commit/be03102) of
`main`, problem `hadwiger-nelson-moser-final`, max 100 turns,
`HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`. Trace at
`/tmp/agent-hadwiger-nelson-moser-final.json`. Result mirror at
`${TMPDIR}/harness-runs/agent-hadwiger-nelson-moser-final.json`.
The full Lean source compiles standalone under Lean 4.29.1 + Mathlib.

**Server launch requirement** (recurring):
`PATH="$HOME/.elan/bin:$PATH"` so the Lean REPL spawns lake correctly.

## Across the four-run series

| Metric | Run 1 | Run 2 | Run 3 | **Run 4** |
|---|---:|---:|---:|---:|
| Steps | 45 | 31 | 456 | **213** |
| `__no_call__` rate | 49% | 13% | 5.5% | **7%** |
| Edges verified (of 11) | 0 | 0 | 9 | **11** |
| Audit attempts | 0 | 0 | 10 | **16** |
| Done attempts | 0 | 0 | 4 (all blocked) | **4 (1 success)** |
| Final theorem | — | — | built but blocked | **shipped** |

Run-to-run, the harness's Lean engagement deepened (`__no_call__` rate
collapsed once Lean was operational; pre-staging unlocked the heavier
proof work). The audit gate stayed consistently strict; only run 4's
explicit framing rules + hand-verified prelude allowed it to pass.
