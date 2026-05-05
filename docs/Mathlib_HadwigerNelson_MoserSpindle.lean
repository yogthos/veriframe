/-
Copyright (c) 2026 Dmitri Sotnikov. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: Dmitri Sotnikov
-/
import Mathlib.Analysis.SpecialFunctions.Pow.Real
import Mathlib.Combinatorics.SimpleGraph.Coloring
import Mathlib.Tactic.FinCases
import Mathlib.Tactic.Linarith
import Mathlib.Tactic.NormNum
import Mathlib.Tactic.Positivity
import Mathlib.Tactic.Ring

/-!
# The Hadwiger–Nelson problem: $\chi(\mathbb{R}^2) \geq 4$

The **Hadwiger–Nelson problem** (Edward Nelson, 1950) asks for the
chromatic number of the *unit-distance graph* on the Euclidean plane:
the minimum number of colors such that no two points of $\mathbb{R}^2$
at distance exactly $1$ share a color. Currently $5 \leq \chi(\mathbb{R}^2)
\leq 7$, with both bounds open.

This file establishes the lower bound $\chi(\mathbb{R}^2) \geq 4$ via the
**Moser spindle** (Moser–Moser, 1961): an explicit $7$-vertex unit-distance
graph that is not $3$-colorable. The construction consists of two unit
rhombi (each a pair of equilateral triangles glued along an edge) sharing
a vertex, with the second rhombus rotated so that one pair of distant
vertices is also at unit distance.

## Main declarations

* `HadwigerNelson.MoserSpindle.adj` : Boolean adjacency for the abstract
  $7$-vertex Moser spindle graph.
* `HadwigerNelson.MoserSpindle.graph` : the abstract Moser spindle as a
  `SimpleGraph (Fin 7)`.
* `HadwigerNelson.MoserSpindle.embed` : the standard embedding
  `Fin 7 → ℝ × ℝ` realising the Moser spindle as a unit-distance subgraph
  of the plane (the rotation angle has $\cos = 5/6$, $\sin = \sqrt{11}/6$).
* `HadwigerNelson.unitDistanceGraph` : the unit-distance graph on
  $\mathbb{R}^2$.

## Main results

* `HadwigerNelson.MoserSpindle.not_colorable_three` : the abstract Moser
  spindle has no proper $3$-coloring (verified by `native_decide`).
* `HadwigerNelson.MoserSpindle.chromaticNumber_ge_four` : the abstract
  Moser spindle's chromatic number is at least $4$.
* `HadwigerNelson.MoserSpindle.embed_edge_distSq` : every Moser spindle
  edge has unit Euclidean squared-distance under the embedding.
* `HadwigerNelson.chromaticNumber_unitDistanceGraph_ge_four` :
  $4 \leq \chi(\mathbb{R}^2)$ (the main theorem).

## References

* L. Moser and W. Moser, *Solution to Problem 10*, Canadian Math. Bull. 4
  (1961), 187–189.
* A. Soifer, *The Mathematical Coloring Book*, Springer, 2009.
* A. de Grey, *The chromatic number of the plane is at least 5*,
  Geombinatorics 28 (2018), 18–31. (Strengthens the lower bound to $5$;
  the formalization here is for the textbook $\geq 4$ bound.)

## Tags

Hadwiger–Nelson problem, chromatic number of the plane, Moser spindle,
unit-distance graph
-/

namespace HadwigerNelson

/-! ### The unit-distance graph on `ℝ × ℝ` -/

/-- The **unit-distance graph** on the Euclidean plane $\mathbb{R}^2$:
two distinct points are adjacent iff they are at Euclidean distance
exactly $1$. -/
def unitDistanceGraph : SimpleGraph (ℝ × ℝ) where
  Adj p q := p ≠ q ∧ (p.1 - q.1) ^ 2 + (p.2 - q.2) ^ 2 = 1
  symm := by
    intro p q ⟨hne, hd⟩
    refine ⟨hne.symm, ?_⟩
    have : (q.1 - p.1) ^ 2 + (q.2 - p.2) ^ 2
         = (p.1 - q.1) ^ 2 + (p.2 - q.2) ^ 2 := by ring
    rw [this]; exact hd
  loopless := ⟨fun _ ⟨hne, _⟩ => hne rfl⟩

namespace MoserSpindle

/-! ### The abstract Moser spindle (`SimpleGraph (Fin 7)`)

Vertices `0..3` form the first unit rhombus (triangles `{0,1,2}` and
`{1,2,3}` glued along edge `{1,2}`). Vertices `0,4,5,6` form the second
unit rhombus. Edge `{3,6}` connects them; the rotation angle for the
second rhombus has $\cos = 5/6$, ensuring this edge also has unit length.
-/

/-- Boolean adjacency for the Moser spindle: 11 unordered edges. -/
def adj : Fin 7 → Fin 7 → Bool
  | 0, 1 => true | 1, 0 => true | 0, 2 => true | 2, 0 => true
  | 1, 2 => true | 2, 1 => true | 1, 3 => true | 3, 1 => true
  | 2, 3 => true | 3, 2 => true | 0, 4 => true | 4, 0 => true
  | 0, 5 => true | 5, 0 => true | 4, 5 => true | 5, 4 => true
  | 4, 6 => true | 6, 4 => true | 5, 6 => true | 6, 5 => true
  | 3, 6 => true | 6, 3 => true | _, _ => false

/-- A function `Fin 7 → Fin k` is a *proper* coloring of the Moser spindle
iff it assigns distinct colors to every adjacent pair of vertices. -/
def isProperColoring {k : ℕ} (f : Fin 7 → Fin k) : Bool :=
  decide (∀ i j : Fin 7, adj i j = true → f i ≠ f j)

/-- The Moser spindle has no proper $3$-coloring. Verified by exhaustive
search over $3^7 = 2187$ candidate functions `Fin 7 → Fin 3`. -/
theorem no_three_coloring : ¬ ∃ f : Fin 7 → Fin 3, isProperColoring f = true := by
  native_decide

/-- The Moser spindle as a `SimpleGraph (Fin 7)`. -/
def graph : SimpleGraph (Fin 7) where
  Adj i j := adj i j = true
  symm := by intro i j h; fin_cases i <;> fin_cases j <;> simp_all [adj]
  loopless := ⟨by intro i h; fin_cases i <;> simp [adj] at h⟩

instance : DecidableRel graph.Adj := fun i j => by unfold graph; exact inferInstance

/-- The abstract Moser spindle is not $3$-colorable as a `SimpleGraph`. -/
theorem not_colorable_three : ¬ graph.Colorable 3 := by
  intro h
  obtain ⟨c⟩ := h
  apply no_three_coloring
  refine ⟨fun i => c i, ?_⟩
  unfold isProperColoring
  simp only [decide_eq_true_eq]
  intro i j hadj
  exact c.valid hadj

/-- The Moser spindle's chromatic number is at least $4$. -/
theorem chromaticNumber_ge_four : 4 ≤ graph.chromaticNumber := by
  by_contra h
  rw [not_le] at h
  have hle : graph.chromaticNumber ≤ 3 := by
    have : graph.chromaticNumber < (3 : ℕ∞) + 1 := h
    exact Order.le_of_lt_add_one this
  have : graph.Colorable 3 := by
    rw [show (3 : ℕ∞) = ((3 : ℕ) : ℕ∞) from rfl] at hle
    exact (SimpleGraph.chromaticNumber_le_iff_colorable).mp hle
  exact not_colorable_three this

/-! ### The unit-distance embedding `Fin 7 → ℝ × ℝ`

Standard placement: rhombus 1 is in the upper half-plane with $v_0$ at the
origin, $v_1$ at $(1, 0)$. Rhombus 2 is the rotation of rhombus 1 about
$v_0$ by an angle $\theta$ with $\cos\theta = 5/6$, $\sin\theta = \sqrt{11}/6$,
so that $|v_3 - v_6| = 1$ as required. -/

/-- The standard embedding of the Moser spindle's $7$ vertices into
$\mathbb{R}^2$. The rotation angle for the second rhombus has
$\cos = 5/6$, $\sin = \sqrt{11}/6$. -/
noncomputable def embed : Fin 7 → ℝ × ℝ
  | 0 => (0, 0)
  | 1 => (1, 0)
  | 2 => (1/2, Real.sqrt 3 / 2)
  | 3 => (3/2, Real.sqrt 3 / 2)
  | 4 => (5/6, Real.sqrt 11 / 6)
  | 5 => ((5 - Real.sqrt 33) / 12, (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
  | 6 => ((15 - Real.sqrt 33) / 12, (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)

/-! #### Per-edge unit-distance lemmas

The 11 edges of the Moser spindle are checked one at a time. Edges of
rhombus 1 use only `Real.sqrt 3`. The edge `{0, 4}` uses `Real.sqrt 11`.
Edges involving `{5, 6}` use the multiplicative identity
$\sqrt 3 \cdot \sqrt{11} = \sqrt{33}$. The two hardest edges, `{5, 6}` and
`{3, 6}`, require pre-simplifying $\Delta x$ and $\Delta y$ via `ring`
before invoking `nlinarith`. -/

private theorem embed_edge_0_1 :
    ((embed 0).1 - (embed 1).1) ^ 2 + ((embed 0).2 - (embed 1).2) ^ 2 = 1 := by
  show ((0 : ℝ) - 1) ^ 2 + ((0 : ℝ) - 0) ^ 2 = 1; norm_num

private theorem embed_edge_0_2 :
    ((embed 0).1 - (embed 2).1) ^ 2 + ((embed 0).2 - (embed 2).2) ^ 2 = 1 := by
  show ((0 : ℝ) - 1/2) ^ 2 + ((0 : ℝ) - Real.sqrt 3 / 2) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

private theorem embed_edge_1_2 :
    ((embed 1).1 - (embed 2).1) ^ 2 + ((embed 1).2 - (embed 2).2) ^ 2 = 1 := by
  show ((1 : ℝ) - 1/2) ^ 2 + ((0 : ℝ) - Real.sqrt 3 / 2) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

private theorem embed_edge_1_3 :
    ((embed 1).1 - (embed 3).1) ^ 2 + ((embed 1).2 - (embed 3).2) ^ 2 = 1 := by
  show ((1 : ℝ) - 3/2) ^ 2 + ((0 : ℝ) - Real.sqrt 3 / 2) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

private theorem embed_edge_2_3 :
    ((embed 2).1 - (embed 3).1) ^ 2 + ((embed 2).2 - (embed 3).2) ^ 2 = 1 := by
  show ((1 : ℝ)/2 - 3/2) ^ 2 + (Real.sqrt 3 / 2 - Real.sqrt 3 / 2) ^ 2 = 1; ring

private theorem embed_edge_0_4 :
    ((embed 0).1 - (embed 4).1) ^ 2 + ((embed 0).2 - (embed 4).2) ^ 2 = 1 := by
  show ((0 : ℝ) - 5/6) ^ 2 + ((0 : ℝ) - Real.sqrt 11 / 6) ^ 2 = 1
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  nlinarith [h11]

private theorem embed_edge_0_5 :
    ((embed 0).1 - (embed 5).1) ^ 2 + ((embed 0).2 - (embed 5).2) ^ 2 = 1 := by
  show ((0 : ℝ) - (5 - Real.sqrt 33) / 12) ^ 2
     + ((0 : ℝ) - (Real.sqrt 11 + 5 * Real.sqrt 3) / 12) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

private theorem embed_edge_4_5 :
    ((embed 4).1 - (embed 5).1) ^ 2 + ((embed 4).2 - (embed 5).2) ^ 2 = 1 := by
  show ((5 : ℝ)/6 - (5 - Real.sqrt 33) / 12) ^ 2
     + (Real.sqrt 11 / 6 - (Real.sqrt 11 + 5 * Real.sqrt 3) / 12) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

private theorem embed_edge_4_6 :
    ((embed 4).1 - (embed 6).1) ^ 2 + ((embed 4).2 - (embed 6).2) ^ 2 = 1 := by
  show ((5 : ℝ)/6 - (15 - Real.sqrt 33) / 12) ^ 2
     + (Real.sqrt 11 / 6 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12) ^ 2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

private theorem embed_edge_5_6 :
    ((embed 5).1 - (embed 6).1) ^ 2 + ((embed 5).2 - (embed 6).2) ^ 2 = 1 := by
  show (((5 : ℝ) - Real.sqrt 33) / 12 - ((15 : ℝ) - Real.sqrt 33) / 12) ^ 2
     + ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12
        - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12) ^ 2 = 1
  have h_dx : (((5 : ℝ) - Real.sqrt 33) / 12
               - ((15 : ℝ) - Real.sqrt 33) / 12) = -5/6 := by ring
  have h_dy : ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12
               - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
             = -Real.sqrt 11 / 6 := by ring
  rw [h_dx, h_dy]
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  nlinarith [h11]

private theorem embed_edge_3_6 :
    ((embed 3).1 - (embed 6).1) ^ 2 + ((embed 3).2 - (embed 6).2) ^ 2 = 1 := by
  show ((3 : ℝ)/2 - ((15 : ℝ) - Real.sqrt 33) / 12) ^ 2
     + (Real.sqrt 3 / 2 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12) ^ 2 = 1
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

/-- The unit-distance squared-distance condition is symmetric in the two
endpoints. -/
private theorem distSq_symm (i j : Fin 7)
    (h : ((embed i).1 - (embed j).1) ^ 2 + ((embed i).2 - (embed j).2) ^ 2 = 1) :
    ((embed j).1 - (embed i).1) ^ 2 + ((embed j).2 - (embed i).2) ^ 2 = 1 := by
  have : ((embed j).1 - (embed i).1) ^ 2 + ((embed j).2 - (embed i).2) ^ 2
       = ((embed i).1 - (embed j).1) ^ 2 + ((embed i).2 - (embed j).2) ^ 2 := by ring
  rw [this]; exact h

/-- Every edge of the abstract Moser spindle has unit Euclidean
squared-distance under the embedding. -/
theorem embed_edge_distSq : ∀ i j : Fin 7, adj i j = true →
    ((embed i).1 - (embed j).1) ^ 2 + ((embed i).2 - (embed j).2) ^ 2 = 1 := by
  intro i j hadj
  fin_cases i <;> fin_cases j <;> simp_all [adj] <;> first
    | exact embed_edge_0_1 | exact distSq_symm 0 1 embed_edge_0_1
    | exact embed_edge_0_2 | exact distSq_symm 0 2 embed_edge_0_2
    | exact embed_edge_1_2 | exact distSq_symm 1 2 embed_edge_1_2
    | exact embed_edge_1_3 | exact distSq_symm 1 3 embed_edge_1_3
    | exact embed_edge_2_3 | exact distSq_symm 2 3 embed_edge_2_3
    | exact embed_edge_0_4 | exact distSq_symm 0 4 embed_edge_0_4
    | exact embed_edge_0_5 | exact distSq_symm 0 5 embed_edge_0_5
    | exact embed_edge_4_5 | exact distSq_symm 4 5 embed_edge_4_5
    | exact embed_edge_4_6 | exact distSq_symm 4 6 embed_edge_4_6
    | exact embed_edge_5_6 | exact distSq_symm 5 6 embed_edge_5_6
    | exact embed_edge_3_6 | exact distSq_symm 3 6 embed_edge_3_6

end MoserSpindle

/-! ### The main theorem: $4 \leq \chi(\mathbb{R}^2)$ -/

/-- The chromatic number of the Euclidean plane is at least $4$.

The proof goes through the Moser spindle: any proper coloring of the
unit-distance graph on $\mathbb{R}^2$ pulls back along the embedding
`MoserSpindle.embed` to a proper coloring of the abstract Moser spindle.
Since `MoserSpindle.not_colorable_three` rules out a proper $3$-coloring
of the abstract spindle, no proper $3$-coloring of the plane exists, so
$\chi(\mathbb{R}^2) \geq 4$.
-/
theorem chromaticNumber_unitDistanceGraph_ge_four :
    4 ≤ unitDistanceGraph.chromaticNumber := by
  have h_not_three : ¬ unitDistanceGraph.Colorable 3 := by
    intro ⟨c⟩
    apply MoserSpindle.not_colorable_three
    refine ⟨{
      toFun := fun i => c (MoserSpindle.embed i),
      map_rel' := by
        intro i j hij
        have hadj : MoserSpindle.adj i j = true := hij
        have hd : ((MoserSpindle.embed i).1 - (MoserSpindle.embed j).1) ^ 2
                + ((MoserSpindle.embed i).2 - (MoserSpindle.embed j).2) ^ 2 = 1 :=
          MoserSpindle.embed_edge_distSq i j hadj
        have hne : MoserSpindle.embed i ≠ MoserSpindle.embed j := by
          intro heq
          rw [heq] at hd
          have : ((MoserSpindle.embed j).1 - (MoserSpindle.embed j).1) ^ 2
               + ((MoserSpindle.embed j).2 - (MoserSpindle.embed j).2) ^ 2 = 0 := by
            ring
          linarith
        exact c.valid ⟨hne, hd⟩
    }⟩
  by_contra h
  rw [not_le] at h
  have hle : unitDistanceGraph.chromaticNumber ≤ 3 := by
    have : unitDistanceGraph.chromaticNumber < (3 : ℕ∞) + 1 := h
    exact Order.le_of_lt_add_one this
  apply h_not_three
  rw [show (3 : ℕ∞) = ((3 : ℕ) : ℕ∞) from rfl] at hle
  exact (SimpleGraph.chromaticNumber_le_iff_colorable).mp hle

end HadwigerNelson
