# Open-Problem Candidates for the Harness

The harness wraps an LLM with three formal-verification engines (SWI-Prolog,
Z3, Lean+Mathlib). Its niche is **claim-and-check** at speed: the model
proposes, the engines validate. That niche fits a narrow but real class of
problems: ones where *finding* an answer is hard but *checking* it is cheap,
and where the LLM's broad cross-subfield reading might surface a path a
specialist would miss.

This document lists candidate problems we considered, ranked by fit, and
records the rationale.

---

## Selection criteria

A problem is a good candidate if:

1. **Concrete and bounded.** A finite witness can settle a specific instance.
2. **Open at small/moderate scales.** Either the conjecture is open in
   general, or specific small instances are unresolved.
3. **Verification is cheaper than discovery.** Encoding the witness check in
   SMT, Prolog, or Lean takes minutes, not weeks.
4. **The state of the art uses computers heavily.** The literature already
   leans on SAT/SMT, brute-force search, or computer-assisted construction —
   so we're not bringing a knife to a gunfight.
5. **The LLM has read the relevant literature.** Cross-pollination between
   subfields is where LLMs have an asymmetric edge.

---

## Candidates, ranked

### 1. Erdős-style discrepancy on a fresh variant *(top pick)*

**What.** Find a ±1 coloring of `[n]` minimizing the maximum sum along
some structured family of subsets (3-APs, geometric progressions, divisor
chains, …). Variants of the original Erdős discrepancy problem are open at
specific bounds.

**Why it fits.** Konev & Lisitsa (2014) used SAT to find a length-1160
±1 sequence with discrepancy 2; Tao then proved the general bound.
Specific cousins remain unresolved at the per-instance level. The LLM
proposes a construction (multiplicative character, Thue-Morse-like,
character-sum-based) and the harness verifies via Z3.

**Reward signal.** Match or beat a published lower bound for a specific
variant.

### 2. 3-AP-free subsets at moderate `n` (Salem–Spencer / Behrend)

**What.** Find the largest subset of `{1, …, n}` with no 3-term arithmetic
progression. `r_3(n)` is computed exactly in OEIS up to roughly `n ≤ 150`;
beyond that, only bounds are known.

**Why it fits.** Z3 verifies a candidate set in milliseconds. The LLM has
read Behrend, Elkin, Croot–Lev–Pach, and the cap-set literature; it can
propose constructions blending these. Beating the Behrend construction at
moderate `n` is plausible.

**Risk.** "Improvement" claims need careful comparison to published
bounds — easy to think you've improved when you've matched.

### 3. Costas-array-style permutation problems

**What.** A Costas array is a permutation `π : [n] → [n]` whose displacement
vectors `(j-i, π(j)-π(i))` are all distinct. Existence at certain `n` was
historically open and resolved by computer search.

**Why it fits.** Verification is a simple all-different SMT query. The LLM
can propose constructions from the Welch/Lempel families and their hybrids
— deep number-theoretic structure where cross-domain insight matters.

**Risk.** The frontier moves; openness claims need to be checked.

### 4. Specific generalised Ramsey numbers `R(C_m, K_n)` etc.

**What.** Avoid `R(5,5)` (a 60-year quagmire). Instead target small
*generalised* Ramsey numbers — tree-vs-clique, off-diagonal hypergraph,
Schur-number variants — where exact small values are open.

**Why it fits.** The harness already proved `R(3,3) = 6` end-to-end. The
engineering carries over; only the encoding changes. The LLM picks the
target family from the literature; Z3 verifies.

**Risk.** Hard to find an instance that's open *and* tractable in a
reasonable timeout.

### 5. Combinatorial identities flagged "no elementary proof known"

**What.** OEIS and the partition-theory literature contain identities
verified empirically but lacking a clean proof. The LLM proposes a
bijection or generating-function argument; Lean+Mathlib verifies.

**Why it fits.** Mathlib is strong on `Finset` and basic combinatorics.
Lower glamour but higher success probability.

**Risk.** Most candidates that sit at this level have been picked over.

---

## Selected experiment

**Pick: a 3-AP-free-subset construction problem at moderate `n`.** Concrete,
SMT-verifiable, open at the chosen scale, and forces the LLM to draw on
multiple corners of additive combinatorics.

The plan:

1. Add a problem entry at `n = 300` or similar, asking the LLM to find a
   3-AP-free subset of `{1, …, n}` as large as possible.
2. Frame the prompt to *invite cross-subfield range*: the model must
   propose 3–5 candidate constructions from different fields, evaluate
   them, and commit.
3. Run with `deepseek-reasoner` (no tool-use time pressure).
4. Verify via Z3 (or direct check) that the produced subset is genuinely
   3-AP-free.
5. Compare the achieved size to Behrend's construction at the same `n`
   and to known upper bounds.

Worst case: a clean demo of the harness on an open-ended problem.
Best case: a construction worth writing down.

---

## Prompt-shape principles

For open problems, the prompt structure that worked for closed problems
(narrow "prove this") is wrong. Replace with:

- **Range first, commit later.** "Propose 3–5 candidate approaches from
  *different* subfields before picking one."
- **Verification failure is data.** "If the engine rejects your candidate,
  explain what you learned and either repair or pivot."
- **Explicit reward gradient.** "Beating bound X is a major win. Matching
  X is a useful negative. Hand-wave is failure."
- **No false modesty about openness.** State that the problem is open,
  what the current bounds are, and that the model is genuinely contributing
  if it produces a verifiable witness near the frontier.
