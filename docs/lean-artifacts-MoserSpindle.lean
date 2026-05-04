import Mathlib

def spindleAdj : Fin 7 → Fin 7 → Bool
  | 0, 1 => true | 1, 0 => true | 0, 2 => true | 2, 0 => true
  | 1, 2 => true | 2, 1 => true | 1, 3 => true | 3, 1 => true
  | 2, 3 => true | 3, 2 => true | 0, 4 => true | 4, 0 => true
  | 0, 5 => true | 5, 0 => true | 4, 5 => true | 5, 4 => true
  | 4, 6 => true | 6, 4 => true | 5, 6 => true | 6, 5 => true
  | 3, 6 => true | 6, 3 => true | _, _ => false

def isProperColoring {k : ℕ} (f : Fin 7 → Fin k) : Bool :=
  decide (∀ i j : Fin 7, spindleAdj i j = true → f i ≠ f j)

theorem no_3_coloring : ¬ ∃ f : Fin 7 → Fin 3, isProperColoring f = true := by native_decide

def moserSpindle : SimpleGraph (Fin 7) where
  Adj i j := spindleAdj i j = true
  symm := by intro i j h; fin_cases i <;> fin_cases j <;> simp_all [spindleAdj]
  loopless := ⟨by intro i h; fin_cases i <;> simp [spindleAdj] at h⟩

instance : DecidableRel moserSpindle.Adj := fun i j => by unfold moserSpindle; exact inferInstance

theorem moserSpindle_not_colorable_3 : ¬ moserSpindle.Colorable 3 := by
  intro h
  obtain ⟨c⟩ := h
  apply no_3_coloring
  refine ⟨fun i => c i, ?_⟩
  unfold isProperColoring
  simp only [decide_eq_true_eq]
  intro i j hadj
  exact c.valid hadj

def planeUnitDistanceGraph : SimpleGraph (ℝ × ℝ) where
  Adj p q := p ≠ q ∧ (p.1 - q.1)^2 + (p.2 - q.2)^2 = 1
  symm := by
    intro p q ⟨hne, hd⟩
    refine ⟨hne.symm, ?_⟩
    have : (q.1 - p.1)^2 + (q.2 - p.2)^2 = (p.1 - q.1)^2 + (p.2 - q.2)^2 := by ring
    rw [this]; exact hd
  loopless := ⟨fun p ⟨hne, _⟩ => hne rfl⟩

noncomputable def f : Fin 7 → ℝ × ℝ
  | 0 => (0, 0)
  | 1 => (1, 0)
  | 2 => (1/2, Real.sqrt 3 / 2)
  | 3 => (3/2, Real.sqrt 3 / 2)
  | 4 => (5/6, Real.sqrt 11 / 6)
  | 5 => ((5 - Real.sqrt 33) / 12, (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)
  | 6 => ((15 - Real.sqrt 33) / 12, (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)

-- Each edge as a separate lemma. We'll combine via a big case analysis later.

theorem edge_0_1 : ((f 0).1 - (f 1).1)^2 + ((f 0).2 - (f 1).2)^2 = 1 := by
  show ((0:ℝ) - 1)^2 + ((0:ℝ) - 0)^2 = 1; norm_num

theorem edge_0_2 : ((f 0).1 - (f 2).1)^2 + ((f 0).2 - (f 2).2)^2 = 1 := by
  show ((0:ℝ) - 1/2)^2 + ((0:ℝ) - Real.sqrt 3 / 2)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

theorem edge_1_2 : ((f 1).1 - (f 2).1)^2 + ((f 1).2 - (f 2).2)^2 = 1 := by
  show ((1:ℝ) - 1/2)^2 + ((0:ℝ) - Real.sqrt 3 / 2)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

theorem edge_1_3 : ((f 1).1 - (f 3).1)^2 + ((f 1).2 - (f 3).2)^2 = 1 := by
  show ((1:ℝ) - 3/2)^2 + ((0:ℝ) - Real.sqrt 3 / 2)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  nlinarith [h3]

theorem edge_2_3 : ((f 2).1 - (f 3).1)^2 + ((f 2).2 - (f 3).2)^2 = 1 := by
  show ((1:ℝ)/2 - 3/2)^2 + (Real.sqrt 3 / 2 - Real.sqrt 3 / 2)^2 = 1; ring

theorem edge_0_4 : ((f 0).1 - (f 4).1)^2 + ((f 0).2 - (f 4).2)^2 = 1 := by
  show ((0:ℝ) - 5/6)^2 + ((0:ℝ) - Real.sqrt 11 / 6)^2 = 1
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  nlinarith [h11]

theorem edge_0_5 : ((f 0).1 - (f 5).1)^2 + ((f 0).2 - (f 5).2)^2 = 1 := by
  show ((0:ℝ) - (5 - Real.sqrt 33) / 12)^2 + ((0:ℝ) - (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

theorem edge_4_5 : ((f 4).1 - (f 5).1)^2 + ((f 4).2 - (f 5).2)^2 = 1 := by
  show ((5:ℝ)/6 - (5 - Real.sqrt 33) / 12)^2 + (Real.sqrt 11 / 6 - (Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

theorem edge_4_6 : ((f 4).1 - (f 6).1)^2 + ((f 4).2 - (f 6).2)^2 = 1 := by
  show ((5:ℝ)/6 - (15 - Real.sqrt 33) / 12)^2 + (Real.sqrt 11 / 6 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

theorem edge_5_6 : ((f 5).1 - (f 6).1)^2 + ((f 5).2 - (f 6).2)^2 = 1 := by
  show (((5 : ℝ) - Real.sqrt 33) / 12 - ((15 : ℝ) - Real.sqrt 33) / 12)^2
    + ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h_dx : (((5 : ℝ) - Real.sqrt 33) / 12 - ((15 : ℝ) - Real.sqrt 33) / 12) = -5/6 := by ring
  have h_dy : ((Real.sqrt 11 + 5 * Real.sqrt 3) / 12 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12) = -Real.sqrt 11 / 6 := by ring
  rw [h_dx, h_dy]
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  nlinarith [h11]

theorem edge_3_6 : ((f 3).1 - (f 6).1)^2 + ((f 3).2 - (f 6).2)^2 = 1 := by
  show ((3:ℝ)/2 - ((15 : ℝ) - Real.sqrt 33) / 12)^2 + (Real.sqrt 3 / 2 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12)^2 = 1
  have h_dx : ((3 : ℝ)/2 - ((15 : ℝ) - Real.sqrt 33) / 12) = (3 + Real.sqrt 33) / 12 := by ring
  have h_dy : (Real.sqrt 3 / 2 - (3 * Real.sqrt 11 + 5 * Real.sqrt 3) / 12) = (Real.sqrt 3 - 3 * Real.sqrt 11) / 12 := by ring
  rw [h_dx, h_dy]
  have h3 : Real.sqrt 3 * Real.sqrt 3 = 3 := Real.mul_self_sqrt (by positivity)
  have h11 : Real.sqrt 11 * Real.sqrt 11 = 11 := Real.mul_self_sqrt (by positivity)
  have h33 : Real.sqrt 33 * Real.sqrt 33 = 33 := Real.mul_self_sqrt (by positivity)
  have h3_11 : Real.sqrt 3 * Real.sqrt 11 = Real.sqrt 33 := by
    rw [← Real.sqrt_mul (by positivity : (3 : ℝ) ≥ 0)]; norm_num
  nlinarith [h3, h11, h33, h3_11]

-- A small helper for symmetric edges: if (f i, f j) has squared-distance 1,
-- so does (f j, f i).
theorem distSq_symm (i j : Fin 7)
    (h : ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 = 1) :
    ((f j).1 - (f i).1)^2 + ((f j).2 - (f i).2)^2 = 1 := by
  have : ((f j).1 - (f i).1)^2 + ((f j).2 - (f i).2)^2
       = ((f i).1 - (f j).1)^2 + ((f i).2 - (f j).2)^2 := by ring
  rw [this]; exact h

-- Combined: any spindle-edge has unit-distance under f.
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
