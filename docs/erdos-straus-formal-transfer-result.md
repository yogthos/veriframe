# Erdős–Straus, $n \equiv 1 \pmod 4$ — Formal-Technique-Transfer Run

## Summary

The harness, on commit `3c53f00`, attempted the residual class
$n \equiv 1 \pmod 4$ of the Erdős–Straus conjecture under a
*formal-technique-transfer* framing: the prompt explicitly invited
the model to import established formal techniques from
non-obvious disciplines (algebraic geometry, additive combinatorics,
information theory, …) and apply them rigorously, citing the
Croot–Lev–Pach polynomial-method breakthrough on cap sets and the
Marton–Tao–Green–Manners entropy resolution of the Polynomial
Freiman–Ruzsa conjecture as precedents.

**Outcome (high level):**

- Re-verified the prior runs' three residue classes ($n \equiv
  0, 2, 3 \pmod 4$) in fresh Lean sessions.
- B3 produced a **Z3-verified negative result**: no linear-in-$n$
  integer parameterization solves the Erdős–Straus equation
  identically for all $n \equiv 1 \pmod{24}$.
- The model attempted multiple cross-disciplinary technique
  imports (Combinatorial Nullstellensatz, modular reductions,
  Gröbner bases) — none yielded a verified positive theorem on
  the open class, but the search itself was substantive.
- 99 minutes wall-clock, 216 turns aggregated, 35 verified
  artifacts (10 confirmed, 17 existential, 8 refuted).

Final shipped result (B1): the existing 75 % residue-class
formalization, plus the negative result from B3 cited as evidence
that genuinely nonlinear techniques are required for the
remaining 25 %.

## The negative result (the new piece)

### Theorem (B3, Z3-verified)

There do not exist integers $a, b, c, d, e, f$ such that the
parametric triple
$$
x \;=\; an + b, \quad y \;=\; cn + d, \quad z \;=\; en + f
$$
satisfies $x, y, z > 0$ and the cleared-denominator equation
$$
4xyz \;=\; n(xy + xz + yz) \tag{$\ast$}
$$
**identically** for all integers $n$ with $n \equiv 1 \pmod{24}$.

**Significance.** This rules out the simplest natural extension
of the Mordell/Webb/Schinzel constructions to the residual
sub-residue class. The published constructions for $n \equiv 0,
2, 3 \pmod 4$ all have the form "pick $x$ as a small linear
function of $n$ (e.g., $x = (n + 3)/4$), and $y$, $z$ as
multiplicative composites of $x$ and $n$." The negative result
shows that **no linear $(x, y, z)$ in $n$ can serve uniformly**
over $n \equiv 1 \pmod{24}$.

### Proof (mathematical)

Substitute $x = an + b$, $y = cn + d$, $z = en + f$ into ($\ast$)
and expand both sides as polynomials in $n$:

\begin{align*}
\text{LHS:}\quad 4xyz &= 4ace\,n^3 + 4(acf + ade + bce)\,n^2 \\
                     &\quad +\; 4(adf + bcf + bde)\,n + 4bdf, \\[4pt]
\text{RHS:}\quad n\bigl(xy + xz + yz\bigr)
   &= (ac + ae + ce)\,n^3 + (ad + bc + af + be + cf + de)\,n^2 \\
   &\quad +\; (bd + bf + df)\,n.
\end{align*}

If ($\ast$) holds for all $n \equiv 1 \pmod{24}$, the polynomial
$4xyz - n(xy + xz + yz)$ vanishes on an infinite set; since it
has degree at most 3, by the Vandermonde / interpolation
argument it vanishes identically. Equating coefficients gives
the system

\begin{align*}
4ace &\;=\; ac + ae + ce, \tag{$n^3$} \\
4(acf + ade + bce) &\;=\; ad + bc + af + be + cf + de, \tag{$n^2$} \\
4(adf + bcf + bde) &\;=\; bd + bf + df, \tag{$n^1$} \\
4bdf &\;=\; 0. \tag{$n^0$}
\end{align*}

**From $4bdf = 0$:** at least one of $b, d, f$ is zero. WLOG
$b = 0$ (the other cases are symmetric in $(x, y, z)$).

With $b = 0$, the $n^1$ equation becomes $4adf = df$, i.e.,
$df(4a - 1) = 0$. Since $4a - 1 \neq 0$ for integer $a$, we get
$df = 0$, so $d = 0$ or $f = 0$.

**Sub-case $b = d = 0$:** $x = an$, $y = cn$, $z = en + f$.
The $n^3$ equation reduces to $4ace = ac + ae + ce$. Dividing
by $ace$ (assuming all positive),
$$
4 \;=\; \frac{1}{a} + \frac{1}{c} + \frac{1}{e}.
$$
For positive integers $a, c, e$, the maximum of the right-hand
side is $1 + 1 + 1 = 3 < 4$. Contradiction.

**Sub-case $b = f = 0$ (and $d = 0$):** symmetric to the above.

In every sub-case, the system has no positive integer solution.
Therefore no linear-in-$n$ integer parameterization solves ($\ast$)
identically on $n \equiv 1 \pmod{24}$. $\square$

### The Z3 formalization

B3 encoded the existence question directly via universal
quantification over $n$, letting Z3's quantifier handler do the
elimination:

```smt
(declare-const a Int) (declare-const b Int)
(declare-const c Int) (declare-const d Int)
(declare-const e Int) (declare-const f Int)
(assert (forall ((n Int))
  (=> (= (mod n 24) 1)
      (and
        (> (+ (* a n) b) 0)
        (> (+ (* c n) d) 0)
        (> (+ (* e n) f) 0)
        (= (* 4 (+ (* a n) b) (+ (* c n) d) (+ (* e n) f))
           (* n (+ (* (+ (* a n) b) (+ (* c n) d))
                   (* (+ (* a n) b) (+ (* e n) f))
                   (* (+ (* c n) d) (+ (* e n) f)))))))))
(check-sat)
```

Z3 returned **`unsat`** — confirming no choice of $(a, b, c, d,
e, f)$ satisfies the conjunction of positivity and the equation
for all $n$ in the residue class. The harness recorded this as
`claimStatus: refuted` because the model's *positive* claim
("such a parameterization exists") was disproven; the
mathematical content is the negation: **no such parameterization
exists**.

### Companion: real-coefficient case

To verify the integrality is what fails (not the polynomial
structure), the model also checked the same encoding over
$\mathbb{R}$ (artifact 19):

```smt
(declare-const a Real) ...
(assert (forall ((n Real))
  (= (* 4 (+ (* a n) b) (+ (* c n) d) (+ (* e n) f))
     (* n ...))))
(check-sat)
```

Z3 returned **`sat`** — over $\mathbb{R}$, real-valued
parameterizations exist (e.g., $(a, b, c, d, e, f) = (1, 0, 1,
0, \tfrac{1}{2}, 0)$ gives $x = y = n$, $z = n/2$, satisfying
$1/n + 1/n + 2/n = 4/n$). For $z = n/2$ to be a positive
integer we need $n$ even, which contradicts $n \equiv 1 \pmod{24}$
(odd). The integrality constraint is exactly where the
parameterization breaks. This companion result confirms the
negative result is about integrality, not structure — closing
off a clear hypothetical attack.

## Cross-disciplinary techniques the model attempted

This run engaged with formal-technique-transfer beyond the
sub-residue retreat of the previous run:

### 1. Combinatorial Nullstellensatz (algebraic combinatorics)

B3 searched Mathlib for `Combinatorial Nullstellensatz Alon
polynomial vanishing on product set` and **found**
`MvPolynomial.combinatorial_nullstellensatz_exists_linearCombination`
and the `_exists_eval_nonzero` variant. The model attempted to
formalize the application but couldn't construct a usable
encoding — the Erdős–Straus polynomial doesn't naturally fit
the Nullstellensatz "vanishes on a grid" hypothesis without
extensive auxiliary work.

This is an honest negative attempt: the technique was found and
considered; it doesn't transfer cleanly without more
infrastructure.

### 2. Modular reduction (mod 3)

B3 attempted `For n ≡ 1 mod 3, the polynomial 4xyz - n(xy + xz +
yz) vanishes at ALL 8 points of (ℤ/3ℤ)^×` and similar local
arguments. These are the kind of "Hasse principle" / local
obstruction arguments that work for some Diophantine equations.
The encodings produced existential SAT results that didn't
package into a reduction.

### 3. Gröbner basis / ideal-theoretic search

B3 searched `Groebner basis polynomial ideal computation` and
inspected Mathlib's `Ideal.ringBasis` and related. Mathlib has
the algebraic primitives but the bridge to Erdős–Straus would
need significant scaffolding (computing the ideal of the variety
$4xyz - n(yz + xz + xy) = 0$, then asking ideal-membership
questions about its integer points). The model didn't construct
this bridge in the budget.

### 4. The negative-result strategy (the one that worked)

After the above didn't yield positive results, B3 attempted to
prove a *meta-theorem*: rather than constructing a solution,
prove that a specific *class* of solution methods cannot work.
The linear-in-$n$ parameterization rule-out is the verified
output of this strategy.

## Per-branch behavior

| Branch | Temp | Turns | Status | Notable |
|---|---|---|---|---|
| B1 | 0.5 | 77 | **DONE** | Re-verified prior, shipped honestly |
| B2 | 0.7 | 18 | culled | |
| B3 | 0.9 | 46 | culled, but produced **the meta-theorem** |
| B4 | 1.1 | 13 | culled | |
| B5 | 1.3 | 62 | culled, 22 mostly-existential artifacts | |

Per-branch temperature variation produced genuine diversity
this time:

- **B1 (low temp)** went safe: re-verified the prior theorems
  and shipped the consolidated answer
- **B3 (mid temp)** went algebraic-combinatorial: searched
  Mathlib for cross-disciplinary primitives, attempted multiple
  formal-technique imports, produced the linear-parameterization
  UNSAT
- **B5 (high temp)** went wild: 22 artifacts, mostly existential
  pseudo-confirms (Z3 SAT without pinned witnesses); culled
  before reaching anything substantive

The conservative-and-wild branches were less productive than the
mid-temperature exploratory branch. This matches the prior
finding that *high temperature alone does not yield creativity*;
it yields noise. The productive cross-disciplinary work happened
in the middle.

## Re-verified prior results (B1's foundation)

B1's first move was to re-verify the prior runs' theorems in its
own Lean session, building from the seeded prompt context:

```lean
import Mathlib

-- Even case
theorem erdos_even (k : ℕ) (hk : k ≠ 0) :
    ∃ x y z : ℕ, x ≠ 0 ∧ y ≠ 0 ∧ z ≠ 0 ∧
    4 * x * y * z = (2 * k) * (x * y + x * z + y * z) := by
  use k, 2*k, 2*k
  refine ⟨hk, ?_, ?_, ?_⟩
  · exact mul_ne_zero (Nat.succ_ne_zero 1) hk
  · exact mul_ne_zero (Nat.succ_ne_zero 1) hk
  ring

-- n ≡ 3 mod 4 case (Mordell construction)
theorem erdos_mod4_3 (k : ℕ) :
    ∃ x y z : ℕ, x ≠ 0 ∧ y ≠ 0 ∧ z ≠ 0 ∧
    4 * x * y * z = (4 * k + 3) * (x * y + x * z + y * z) := by
  let n := 4 * k + 3
  use k+1, n*(k+1)+1, (n*(k+1))*(n*(k+1)+1)
  refine ⟨Nat.succ_ne_zero k, by exact Nat.succ_ne_zero _,
          mul_ne_zero (mul_ne_zero (Nat.succ_ne_zero _)
                                    (Nat.succ_ne_zero k))
                      (by omega), ?_⟩
  dsimp [n]; ring
```

Both compiled cleanly under Lean 4 + Mathlib. The Z3
cross-checks (artifacts 3-5 of the run) independently verified
the same identities.

## Honest assessment of novelty (post literature search)

After writing the initial draft of this report I ran a
literature search to confirm whether the negative result is new.
**It is not.** The result B3 produced is a special case of a
well-known theorem.

### Mordell (1967)

L. J. Mordell proved the following obstruction in *Diophantine
Equations* (1967): a polynomial identity providing an
Erdős–Straus solution for $n \equiv r \pmod p$ can exist only
when $r$ is **not** a quadratic residue mod $p$.

Since $1 = 1^2$ is a quadratic residue mod every prime, **no
polynomial identity** (linear, quadratic, or any degree) can
cover $n \equiv 1 \pmod p$ for any modulus $p$ — including
$n \equiv 1 \pmod 3$, $n \equiv 1 \pmod{24}$, and any finer
sub-residue.

This is more general than B3's result, which handles only
linear parameterizations and the specific residue $n \equiv 1
\pmod{24}$.

### Terence Tao (blog, 2011)

In his "Tag Archives: Erdős–Straus conjecture" series, Tao
states the obstruction explicitly:

> "an application of the quadratic reciprocity law shows that
> these congruence relations cannot eliminate quadratic
> residues, only quadratic non-residues … this rules out any
> approach based on using polynomial combinations of $p$ and
> dividing into cases based on residue classes."

### What this means for the artifact

| Claim | Honest verdict |
|---|---|
| The mathematical result B3 produced | A **special case** of Mordell (1967) |
| Z3-formal verification of this special case | Probably new as a *machine-checkable* artifact (no published SMT/Lean encoding of Mordell's quadratic-residue obstruction that I could find) |
| Cross-disciplinary technique transfer succeeded | **No** — B3 retreated from Combinatorial Nullstellensatz / Gröbner basis to a direct SMT encoding of a known elementary obstruction |
| Strategic value | Modest — confirms (in a machine-checkable form) an obstruction that's been known and explicitly cited for 59 years |

So the run produced **no new mathematics**, and the
cross-disciplinary push **did not succeed in importing a
formal technique that yielded a positive result**. The verified
artifact is a small formalization of a folk-named theorem.

This is an honest negative finding. The harness reproduces
known results in machine-checked form; it did not produce the
kind of cross-disciplinary creative contribution the prompt was
asking for.

### Lessons

1. **Always literature-search verified novel-looking claims.**
   The first draft of this report described B3's result as
   "almost certainly known as a folk observation" — that
   phrasing was too soft. The result is explicitly attributed
   to Mordell (1967), cited by Tao, and prominent enough to
   appear in the Wikipedia article on the conjecture. Without
   the search, the report would have over-claimed novelty.

2. **Cross-disciplinary technique-import is genuinely hard for
   open problems.** Even with explicit prompting and Mathlib's
   Combinatorial Nullstellensatz available, the model defaulted
   (after exploration) to encoding a classical obstruction it
   could verify. This is a structural finding about the
   harness/model interaction, not a defect of the verification
   stack.

3. **The harness's job is rigorous verification, not novelty
   detection.** Distinguishing "novel" from "known" requires
   external literature search; the harness can verify what's
   submitted to it, but it can't tell you whether the same
   result was published in 1967.

## What this run says about the harness

Compared to the prior "creative" attempt, this run:

1. **Engaged with the technique-import directive.** B3 searched
   Mathlib for Nullstellensatz, Gröbner bases, entropy methods.
   The previous run defaulted to standard sub-residue
   decomposition.

2. **Produced a structurally novel artifact.** The linear-
   parameterization meta-theorem isn't a sub-residue construction
   — it's a *negative result* about a class of approaches.

3. **Honestly distinguished verified from speculative.** The
   shipped answer cited the Z3 UNSAT explicitly and didn't
   over-claim.

4. **Mid-temperature exploration was productive.** B3 (temp 0.9)
   was the productive branch, not B5 (temp 1.3). The right
   creativity is *thoughtful imports*, not *random noise*.

The harness is not a creativity engine in the sense of
generating new mathematics, but it can *structure rigorous
exploration* — including formal negative results that are
genuinely useful contributions even when no positive theorem
falls.

## Reproduction

Run on commit
[`3c53f00`](https://github.com/yogthos/veriframe/commit/3c53f00)
of `main`, problem `erdos-straus-mod1-formal-transfer`, max 100
turns, `HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`.
Trace at `/tmp/agent-erdos-straus-mod1-formal-transfer.json`.
The Z3 negative-result encoding (artifact 12 of the trace JSON)
re-verifies cleanly under Z3 ≥ 4.13.

## Open extension targets

Natural follow-ups for the harness:

1. **Quadratic parameterization rule-out.** Extend the Z3
   negative result to $x = a n^2 + b n + c$, $y = \ldots$,
   $z = \ldots$. If the same UNSAT holds, this rules out an
   even broader class of methods. (May not hold; quadratic
   forms might admit identities.)

2. **Formalize the polynomial identity argument in Lean.** Lift
   the Z3 verification to a Lean proof using
   `MvPolynomial.coeff_eq_iff` or similar — getting both Lean
   and SMT to agree on the meta-theorem.

3. **Apply the Combinatorial Nullstellensatz lemma B3 found.**
   With `MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero`
   already in Mathlib, a careful encoding of Erdős–Straus as a
   non-vanishing question could yield a positive result on a
   sub-residue class. The previous run searched the lemma but
   didn't bridge to a verified application.
