# Hadwiger–Nelson Mathlib PR Preparation

## What this is

A refactored, Mathlib-style version of the
$\chi(\mathbb{R}^2) \geq 4$ proof, ready for upstream submission to
Mathlib. Source: `docs/Mathlib_HadwigerNelson_MoserSpindle.lean`.

Compiles cleanly against Lean 4.29.1 + Mathlib (no warnings, no
`sorry`, no errors).

## Mathlib conventions applied

### File-level

- **Apache 2.0 license header** with author attribution.
- **Module-level `/-! ... -/` doc comment** explaining the Hadwiger–Nelson
  conjecture, listing main declarations + main results, and citing the
  three key references (Moser & Moser 1961, Soifer 2009, de Grey 2018).
- **Tags section** for searchability.
- **Targeted `import`s** (only what's needed: `Coloring`, `Pow.Real`,
  `FinCases`, `Linarith`, `NormNum`, `Positivity`, `Ring`).

### Namespacing

| Old name | New name | Visibility |
|---|---|---|
| `spindleAdj` | `HadwigerNelson.MoserSpindle.adj` | public |
| `isProperColoring` | `HadwigerNelson.MoserSpindle.isProperColoring` | public |
| `no_3_coloring` | `HadwigerNelson.MoserSpindle.no_three_coloring` | public |
| `moserSpindle` | `HadwigerNelson.MoserSpindle.graph` | public |
| `moserSpindle_not_colorable_3` | `HadwigerNelson.MoserSpindle.not_colorable_three` | public |
| `moserSpindle_chromaticNumber_ge_4` | `HadwigerNelson.MoserSpindle.chromaticNumber_ge_four` | public |
| `f` | `HadwigerNelson.MoserSpindle.embed` | public |
| `edge_i_j` (×11) | `HadwigerNelson.MoserSpindle.embed_edge_i_j` (×11) | **private** |
| `distSq_symm` | `HadwigerNelson.MoserSpindle.distSq_symm` | private |
| `f_edge_distSq` | `HadwigerNelson.MoserSpindle.embed_edge_distSq` | public |
| `planeUnitDistanceGraph` | `HadwigerNelson.unitDistanceGraph` | public |
| `chiR2_ge_4` | `HadwigerNelson.chromaticNumber_unitDistanceGraph_ge_four` | public |

The 11 per-edge unit-distance lemmas are marked `private` since they're
implementation details of `embed_edge_distSq`. The combined
`embed_edge_distSq` is the public-facing edge property.

### Documentation

Every public declaration has a `/-- ... -/` docstring:
- The unit-distance graph definition explains what unit-distance means.
- The Moser spindle definition references the Moser–Moser construction.
- The embedding's docstring spells out the rotation angle ($\cos = 5/6$,
  $\sin = \sqrt{11}/6$).
- The main theorem's docstring sketches the proof strategy (pull back
  the coloring along the embedding).

A separate `/-! #### ... -/` block above the per-edge lemmas explains the
proof strategy (which sqrt lemmas are needed, why the two hardest edges
need the `ring`-rewrite trick).

## Notes on `native_decide`

The proof of `MoserSpindle.no_three_coloring` uses `native_decide` to
exhaust all $3^7 = 2187$ candidate $3$-colorings. This is **the right
tool** for the size; `decide` exhausts kernel recursion at this scale.

Caveat: **`native_decide` does not work cleanly under the new Lean module
system** (`module` / `public import`) due to native-code linking issues
for `Pi.instFintype`. The PR uses **legacy `import`** style, which is
still widely accepted in Mathlib and works correctly.

When Mathlib's module-system compatibility for `native_decide` is
resolved upstream, this file can be migrated.

## Next steps for upstream submission

1. ✅ File compiles cleanly under Lean 4.29.1 + Mathlib.
2. **Find the right place in the Mathlib file tree.** Suggested:
   `Mathlib/Combinatorics/SimpleGraph/HadwigerNelson.lean` or
   `Mathlib/Combinatorics/SimpleGraph/Coloring/HadwigerNelson.lean`.
3. **Add a `Mathlib.lean` re-export entry** so the file is discoverable.
4. **Run Mathlib CI locally** (or via the bot) to confirm no breakages.
5. **Open a PR** with the body:

```
feat(Combinatorics/SimpleGraph): formalize Hadwiger–Nelson chi(R^2) ≥ 4

Adds a proof of chi(R^2) ≥ 4 (the textbook pre-de-Grey lower bound on
the chromatic number of the plane) via the Moser spindle (Moser & Moser,
1961). The proof uses native_decide to rule out a 3-coloring of the
abstract 7-vertex spindle, then constructs an explicit unit-distance
embedding f : Fin 7 → ℝ × ℝ and pulls back any 3-coloring of the plane
to a 3-coloring of the spindle, contradiction.

Mathlib does not currently have any Hadwiger–Nelson formalization.
This is the foundation for future work toward de Grey's chi ≥ 5 (2018),
Hadwiger's chi ≤ 7 upper bound (1945), and chromatic number bounds in
higher dimensions.

References:
- L. Moser & W. Moser, Solution to Problem 10, Canad. Math. Bull. 4 (1961)
- A. Soifer, The Mathematical Coloring Book, Springer 2009
- A. de Grey, The chromatic number of the plane is at least 5, 2018
```

## Provenance

The proof was developed via the `reasoning-harness` LLM-driven
verification framework, in a four-run sequence:

1. `hadwiger-nelson-chi` — failed, coordinate-first attempt got stuck.
2. `hadwiger-nelson-moser-lean` — failed, SimpleGraph.Coloring decidability.
3. `hadwiger-nelson-moser-embedding` — verified 9 of 11 edges, audit-blocked.
4. `hadwiger-nelson-moser-final` — full ship of the verified pipeline.

Plus hand-verification interleaved between runs 3 and 4 to identify the
missing tactic (`ring`-rewrite of $\Delta x, \Delta y$ before
`nlinarith`) for the inter-rhombus edges $\{5,6\}$ and $\{3,6\}$.

The harness's contribution was iteratively constructing the proof under
audit-gate scrutiny; the mathematics is textbook.
