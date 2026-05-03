# Erdős–Straus, $n \equiv 1 \pmod 4$ — Hasse-Style Prime-Factor Reduction

## Summary

The harness, on commit `3299f88` with a literature-informed
prompt, produced a Lean-formalized reduction theorem for the
$n \equiv 1 \pmod 4$ case of the Erdős–Straus conjecture:
**any $n$ in this class with a prime factor $\equiv 3 \pmod 4$
admits an explicit Erdős–Straus decomposition by scaling
Mordell's identity for that prime factor.**

This is the cleanest "model followed the literature catalog and
produced something genuinely useful" run yet. Comparison to
prior attempts on the same residue class:

| Run | Outcome | Novel? |
|---|---|---|
| Prior (no literature) | sub-residue defaults | No |
| Prior (creative prompting) | rediscovered Mordell's QR obstruction | No |
| Prior (formal-transfer) | rediscovered Mordell (linear-param case) | No |
| **This run (literature-informed)** | **Hasse-style reduction formalized** | **Formally novel; mathematically known** |

Wall-clock 16 min, 115 turns aggregated, 49 verified artifacts
(29 confirmed, 15 existential, 5 refuted). B5 won; all 5
branches contributed.

## What got proven

### Theorem (Hasse-style reduction for $n \equiv 1 \pmod 4$)

Let $n$ be a positive integer with $n \equiv 1 \pmod 4$. If
there exists a prime $q$ such that $q \mid n$ and $q \equiv 3
\pmod 4$, then the Erdős–Straus equation
$$
\frac{4}{n} \;=\; \frac{1}{x} + \frac{1}{y} + \frac{1}{z}
$$
admits a positive-integer solution. The solution is
constructed explicitly: write $q = 4k + 3$ and $n = qm$, then
$$
(x, y, z) \;=\; \bigl(m(k+1),\; m\bigl(q(k+1)+1\bigr),\;
                       m \cdot q(k+1)\bigl(q(k+1)+1\bigr)\bigr).
$$

### Proof structure

The proof factors into two formally verified pieces:

**Lemma 1 (Mordell's identity, $q = 4k + 3$).** Already proven
in prior runs:
$$
\frac{4}{q} \;=\; \frac{1}{k+1} + \frac{1}{q(k+1)+1} +
                  \frac{1}{q(k+1)(q(k+1)+1)}.
$$
Equivalently: $(k+1, q(k+1)+1, q(k+1)(q(k+1)+1))$ is a
`Solution q`.

**Lemma 2 (scaling).** *If $(x, y, z)$ solves
$\frac{4}{n} = \frac{1}{x} + \frac{1}{y} + \frac{1}{z}$, then
$(mx, my, mz)$ solves
$\frac{4}{mn} = \frac{1}{mx} + \frac{1}{my} + \frac{1}{mz}$.*

*Proof.* Both sides scale by $\frac{1}{m}$:
$$
\frac{1}{mx} + \frac{1}{my} + \frac{1}{mz}
\;=\; \frac{1}{m}\Bigl(\frac{1}{x} + \frac{1}{y} + \frac{1}{z}\Bigr)
\;=\; \frac{1}{m} \cdot \frac{4}{n}
\;=\; \frac{4}{mn}.
$$
$\square$

**Combined**: given $n = qm$ with $q = 4k + 3$, apply Lemma 1 to
get a solution for $q$, then apply Lemma 2 with scaling factor
$m$ to lift it to a solution for $n = qm$.

### The Lean formalization (essence)

```lean
import Mathlib

structure Solution (n : ℕ) : Type where
  (x y z : ℕ)
  (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0)
  (h : 4 * x * y * z = n * (x * y + x * z + y * z))

-- Scaling: a Solution for n yields a Solution for nm.
theorem solution_scale (n m : ℕ) (hm : m ≠ 0)
    (s : Solution n) : Solution (m * n) :=
  { x := m * s.x,
    y := m * s.y,
    z := m * s.z,
    hx := mul_ne_zero hm s.hx,
    hy := mul_ne_zero hm s.hy,
    hz := mul_ne_zero hm s.hz,
    h := by
      have := s.h
      ring_nf
      linarith [Nat.mul_pos (Nat.pos_of_ne_zero hm) (Nat.pos_of_ne_zero s.hx)] }

-- Mordell at q = 4k+3 (proven in prior runs).
theorem mordell_3mod4 (k : ℕ) : Solution (4 * k + 3) := …

-- Combined: any n = q * m with q ≡ 3 mod 4 admits a Solution.
theorem hasse_reduction (q m : ℕ) (k : ℕ) (hq : q = 4 * k + 3)
    (hm : m ≠ 0) : Solution (q * m) :=
  solution_scale q m hm (hq ▸ mordell_3mod4 k)
```

The actual artifact code is slightly more elaborate (Z3
cross-checks of concrete instances + Lean theorem closure)
but the structure above captures the proof.

### Concrete verifications

The harness verified the construction for several specific $n$:

| $n$ | factorization | scaling factor $m$ | shipped solution $(x, y, z)$ |
|---|---|---|---|
| $21$ | $3 \cdot 7$ | $3$ | $(6, 45, 630)$ |
| $33$ | $3 \cdot 11$ | $3$ | $(9, 102, 3366)$ |
| $49$ | $7^2$ | $7$ | $(14, 105, 1470)$ |
| $77$ | $7 \cdot 11$ | $11$ | $(22, 165, 2310)$ |
| $105$ | $3 \cdot 5 \cdot 7$ | $15$ | $(30, 225, 3150)$ |

Each was independently SMT-verified via Z3.

*Sanity check for $n = 21$*: $(x, y, z) = (6, 45, 630)$. Then
$\frac{1}{6} + \frac{1}{45} + \frac{1}{630} = \frac{105}{630} +
\frac{14}{630} + \frac{1}{630} = \frac{120}{630} = \frac{4}{21}$
✓.

## Coverage of the conjecture after this run

Combining all prior verified results with this new theorem:

| Class | Status |
|---|---|
| $n \equiv 0 \pmod 2$ | Lean-proved (prior) |
| $n \equiv 0 \pmod 4$ | Lean-proved (prior, refined) |
| $n \equiv 3 \pmod 4$ | Lean-proved, Mordell construction (prior) |
| $n \equiv 5 \pmod 8$ | Lean-proved, sub-residue (prior) |
| $n \equiv 5 \pmod{12}$ | Lean-proved, sub-residue (prior) |
| $n \equiv 1 \pmod 4$, has prime factor $\equiv 3 \pmod 4$ | **Lean-proved, this run (Hasse reduction)** |
| **Residual: $n$ a product of primes all $\equiv 1 \pmod 4$** | **OPEN** |

The genuinely open territory is now **products of primes
$\equiv 1 \pmod 4$** — i.e., $n \in \{5, 13, 17, 25, 29, 37, 41,
53, 5 \cdot 13 = 65, 5 \cdot 17 = 85, 13^2 = 169, 5^3 = 125,
\ldots\}$.

This residual has natural density zero (by Chebotarev / a
Pólya–Vinogradov-style argument: the proportion of integers
with no prime factor in any specific residue class shrinks like
$O((\log N)^{-1/2})$). But it's an infinite set, and proving
the conjecture for all of it is exactly where the historical
difficulty has lived.

## Why this run worked when prior attempts didn't

The key intervention was the **literature-informed prompt**.
Specifically:

1. **An explicit "DO NOT REPRODUCE" list** with attribution
   (Mordell 1967, Webb, Vaughan, etc.) so the model didn't
   waste budget on dead ends.
2. **Six ranked under-explored angles** with specific Lean
   formalisability hints, including angle D (Hasse-style
   prime-factor reduction) which arXiv 2602.20036v2 cites as
   the basis for their density-1 result.
3. **The prior verified Lean theorems pre-staged** in the
   prompt as `lean_define`-able starting material.

With these in hand, B5 went straight to angle D, formalized
the scaling lemma, combined it with the Mordell identity, and
shipped honestly.

The previous "creative" runs failed because the model lacked
the literature catalog and either (a) reproduced Mordell's
1967 obstruction (b) defaulted to standard sub-residue
parameterization. Telling the model what NOT to do was as
important as telling it what to try.

## Honest novelty assessment

**Mathematically novel?** No.

The Hasse-style "use a $q \equiv 3 \pmod 4$ prime factor"
reduction is well-known and is the basis for arXiv
2602.20036v2's density-1 partial result. It's been folklore
since at least the 1960s; arXiv 2602.20036v2 makes it explicit.

**Formally novel?** Probably yes.

The Lean-formalized version (scaling lemma + combined
reduction theorem under the `Solution n` structure)
isn't, as far as I can tell, in Mathlib. As a verified
artifact, it's a small Lean contribution — the kind of
formalization that, lifted to Mathlib's `Rat` /
`Finset.sum_inv` style, could be submitted upstream.

**Strategically meaningful?** Yes.

This is the *correct* next step in formalizing partial
Erdős–Straus results. Combined with the prior runs, the
verified Lean coverage is now:

- All $n \not\equiv 1 \pmod 4$ (3 of 4 mod-4 classes)
- All $n \equiv 5 \pmod 8$ and $n \equiv 5 \pmod{12}$
  sub-residues of $n \equiv 1 \pmod 4$
- All $n \equiv 1 \pmod 4$ with a prime factor $\equiv 3 \pmod 4$

The residual is genuinely small (density zero) and infinitely
dense (in $\mathbb{N}$ but with vanishing density). Real
analytic / algebraic-geometric techniques would be required
to push further.

## Reproduction

Run on commit
[`3299f88`](https://github.com/yogthos/veriframe/commit/3299f88)
of `main`, problem `erdos-straus-mod1-informed`, max 100 turns,
`HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`. Trace at
`/tmp/agent-erdos-straus-mod1-informed.json`. Concrete
verifications for $n = 21, 33, 49, 77, 105$ all re-check
under any modern Z3 (≥ 4.13).

## What's left open

The residual: $n$ a product of primes all $\equiv 1 \pmod 4$,
i.e., $n \in S = \{5, 13, 17, 25, 29, 37, \ldots\}$. The
arXiv 2511.07465 preprint (Nov 2025) claims a constructive
proof for all primes in this set via methods ED1 and ED2;
this is **unverified by peer review** and would be the
natural next target for the harness.

A follow-up run could:

1. Verify the ED2 identity $(4b-1)(4c-1) = 4P\delta + 1$ for
   specific primes $P \in \{5, 13, 17, 29, 37, 41, ...\}$ via
   Z3, either confirming the recent preprint empirically or
   surfacing a counterexample.
2. Formalize a Lean proof of the reduction "if Erdős–Straus
   holds for all primes $P \equiv 1 \pmod 4$, then it holds
   for all $n \equiv 1 \pmod 4$" (a multiplicative lifting
   argument).
3. Apply Mathlib's
   `MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero`
   to the residual class.
