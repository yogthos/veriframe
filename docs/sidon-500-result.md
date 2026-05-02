# Verified Sidon Set in [1, 500] — Run on 2026-05-02

## Summary

The harness, running with `BEAM_WIDTH = 5` parallel branches over
`deepseek-reasoner` (~23 minutes wall-clock, 44 turns aggregated),
produced a **verified Sidon set of size 20** in $\{1, 2, \ldots, 500\}$.
The verification is by Z3 against a sound `(distinct ...)` encoding
of all pairwise sums.

The same run also produced **two false-positive claims** at sizes 24
and 26 due to a buggy SMT-LIB encoding the model chose without
realizing it was incomplete. Those claims do **not** stand. The
genuine result is the size-20 set documented below.

## Result

**Theorem.** Let
$$
S = \{1, 2, 4, 8, 13, 21, 31, 45, 66, 81, 97, 123, 148, 182, 204, 252, 290, 361, 401, 475\}.
$$

Then $S \subset \{1, \ldots, 500\}$, $|S| = 20$, and $S$ is a *Sidon
set*: all pairwise sums $a + b$ with $a, b \in S$ and $a \le b$ are
distinct.

Equivalently: for any $a, b, c, d \in S$ with $a \le b$, $c \le d$,
$$
a + b = c + d \implies (a, b) = (c, d).
$$

This is the **Mian–Chowla sequence** truncated at the 20th term.
Mian–Chowla is the greedy Sidon sequence:
- $a_1 = 1$,
- $a_{n+1}$ = the smallest positive integer such that $\{a_1, \ldots, a_{n+1}\}$
  remains Sidon.

Its first 20 terms (OEIS [A005282](https://oeis.org/A005282)) all lie in $[1, 500]$.

## Proof

Sidon-ness is equivalent to the assertion that the $\binom{|S|+1}{2} = 210$
pairwise sums $a_i + a_j$ (with $1 \le i \le j \le 20$) are pairwise
distinct. Z3 verified this directly: with $a_1, \ldots, a_{20}$ pinned
to the values above, the constraint
$$
\bigl(\text{distinct } a_1+a_1,\ a_1+a_2,\ \ldots,\ a_{20}+a_{20}\bigr)
$$
is satisfiable. Since the values are fixed, satisfiability of the
distinctness constraint *is* the assertion that all sums are distinct.

The exact SMT-LIB submitted by the harness (abbreviated; the real
expansion lists all 210 sums):

```smt
(declare-const a1 Int) ... (declare-const a20 Int)
(assert (= a1 1))   (assert (= a2 2))   (assert (= a3 4))   (assert (= a4 8))
(assert (= a5 13))  (assert (= a6 21))  (assert (= a7 31))  (assert (= a8 45))
(assert (= a9 66))  (assert (= a10 81)) (assert (= a11 97)) (assert (= a12 123))
(assert (= a13 148))(assert (= a14 182))(assert (= a15 204))(assert (= a16 252))
(assert (= a17 290))(assert (= a18 361))(assert (= a19 401))(assert (= a20 475))
(assert (distinct
  (+ a1 a1)  (+ a1 a2)  ...  (+ a1 a20)
  (+ a2 a2)  (+ a2 a3)  ...  (+ a2 a20)
  ...
  (+ a19 a19)(+ a19 a20)
  (+ a20 a20)))
(check-sat)
```

Z3 returned `sat`, confirming the constraint is consistent — i.e.,
all 210 pair sums have distinct integer values, so $S$ is Sidon.

## Independent re-verification

We re-ran the verification using the alternative
existence-of-collision encoding (more economical for human
inspection):

```smt
(declare-const a Int) (declare-const b Int)
(declare-const c Int) (declare-const d Int)
(define-fun inS ((x Int)) Bool (or (= x 1) (= x 2) (= x 4) (= x 8)
  (= x 13) (= x 21) (= x 31) (= x 45) (= x 66) (= x 81) (= x 97)
  (= x 123) (= x 148) (= x 182) (= x 204) (= x 252) (= x 290)
  (= x 361) (= x 401) (= x 475)))
(assert (inS a)) (assert (inS b)) (assert (inS c)) (assert (inS d))
(assert (< a b)) (assert (< c d))
(assert (or (< a c) (and (= a c) (not (= b d)))))
(assert (= (+ a b) (+ c d)))
(check-sat)
```

This says: *do there exist two distinct unordered pairs in $S$ with
the same sum?* Z3 returned `unsat` — no such pairs exist. Both
encodings agree: $S$ is Sidon. ∎

## Comparison to known bounds

The maximum size of a Sidon set in $\{1, \ldots, n\}$, denoted $F_2(n)$,
satisfies
$$
\sqrt{n} - O(n^{1/4}) \le F_2(n) \le \sqrt{n} + O(n^{1/4})
$$
(Erdős–Turán 1941; Lindström 1969). For $n = 500$:
$$
\sqrt{500} \approx 22.36, \qquad 500^{1/4} \approx 4.73.
$$

Singer's projective-plane construction with prime power $q = 23$ gives
a Sidon set of size $q + 1 = 24$ in $[0, q^2 + q] = [0, 552]$; some of
those elements may exceed 500, leaving a slightly smaller residual set
in $[1, 500]$. **The exact value of $F_2(500)$ is not, to my knowledge,
recorded in standard tables**; published constructions in the few-hundred
range typically reach the low-to-mid 20s.

Our verified $|S| = 20$ is below the Singer bound — Mian–Chowla is
intentionally simple and not optimal — but it is a clean, machine-checkable
artifact in the same regime.

A genuinely interesting result would be a verified set of size 23 or
larger. The harness produced *claims* at sizes 24 and 26, but those
claims rest on an unsound encoding (next section).

## Caveat: the false-positive claims

In the same run, the model emitted two larger claims that were
recorded as "confirmed" by the harness:

- **Size 24**: $\{1, 8, 23, 47, 54, 68, 80, 92, 101, 124, 140, 156,
  163, 178, 193, 198, 209, 223, 241, 251, 269, 274, 297, 317\}$.
- **Size 26**: the size-24 set augmented with $\{322, 400\}$.

Both fail an honest Sidon check. Concrete collisions:

- In the size-24 set: $8 + 178 = 186 = 23 + 163$.
- In the size-26 set: $1 + 400 = 401 = 178 + 223$.

The encoding the model used for these claims was:

```smt
(assert (forall ((i Int) (j Int) (k Int) (l Int))
  (=> (and (<= 1 i) (<= i j) (<= j k) (<= k l) (<= l 26)
           (= (+ (a i) (a j)) (+ (a k) (a l))))
      (and (= i k) (= j l)))))
(check-sat)
```

This *looks* like a Sidon assertion but is logically incomplete. The
chain
$$
1 \le i \le j \le k \le l \le 26
$$
forces the indices into a *single ordered tuple* $(i, j, k, l)$.
A genuine pair-vs-pair collision at indices $(i', j')$ vs $(k', l')$
need only satisfy $i' < j'$ and $k' < l'$ as separate orderings,
*not* the joint chain $i' \le j' \le k' \le l'$. The size-26
collision has indices $(1, 26)$ and $(14, 18)$ — which violates the
chain because $26 > 14$.

Z3 evaluated the universal quantifier over the (tiny) set of index
tuples satisfying the chain and found no contradiction, so the
formula is consistent. The model interpreted `sat` as "Sidon
verified," and the harness's `expectedVerdict: "sat"` mechanism
faithfully tagged the artifact as `confirmed`. Neither component is
buggy in isolation — the model chose a wrong encoding, the harness
believed the model's promise.

## How beam search produced the result

The run launched 5 parallel branches, each with its own LLM thread,
Prolog session, and Lean REPL. Branches shared a global failure log
so they could see each other's rejections.

Per-branch outcome:

| Branch | Turns | Status   | What happened                                |
|--------|-------|----------|----------------------------------------------|
| B1     | 6     | culled   | 3 consecutive verify_smt failures            |
| B2     | 6     | culled   | 3 consecutive verify_smt failures            |
| B3     | 13    | culled   | reached the size-20 Mian–Chowla verified set |
| B4     | 7     | culled   | 3 consecutive verify_smt failures            |
| B5     | 12    | culled   | reached the (false-positive) size-24/26 sets |

Aggregate: 44 turns, 40 `verify_smt` calls, 31 captured artifacts,
of which **11** were `claimStatus: confirmed` and **20** were
`refuted`. The genuine verified set came from B3 using a sound
`(distinct sums)` encoding; the false positives came from B5 using
the broken `forall` encoding.

No branch called `done()`. All five eventually accumulated 3
consecutive failures (mostly while trying to grow past their best
verified size) and got culled by the `CULL_THRESHOLD = 3` rule.
The harness terminated with `Beam exhausted` — the result is in
`harness.verifiedArtifacts` but was never declared a final answer.

## Lessons for the harness

1. **The harness cannot validate the model's encoding choice.** Both
   encodings used here are valid SMT-LIB and run cleanly in Z3; one
   is sound for Sidon-ness, the other is not. The harness's
   `expectedVerdict` mechanism establishes a *contract* between
   model and verifier, but it cannot tell whether the model wrote a
   logically complete formula. This is a fundamental limit: the
   harness relies on the model to produce a correct encoding.

2. **Mitigation options**:
   - **Lint encodings** for known antipatterns (e.g., a `forall`
     binding multiple ordering predicates over a small finite set).
   - **Cross-check by re-running with a different encoding** when an
     artifact's claimed size exceeds a threshold.
   - **Explicit pair enumeration template** in the system prompt, so
     the model defaults to the sound `(distinct ...)` form.

3. **Cull-threshold needs care for incremental-growth strategies.**
   B5's grow-by-one approach naturally produces 1 verified
   confirmation followed by some failed extensions (the next
   addition might be incompatible). The current rule cuts these
   branches off too early, even when they're producing the most
   value.

4. **No `done()` ever fired.** A branch with a high-quality verified
   result has no incentive to stop and ship. The model defaulted to
   "try more" until culled. Worth adding a system-prompt rule
   ("if you have a verified result at or near the published bound,
   call done") or an emergency-finalisation hook on the harness side.

## Reproduction

The full run trace is at `/tmp/agent-open-sidon-set-500.json` from
the run on commit
[`fe8e229`](https://github.com/yogthos/veriframe/commit/fe8e229)
(the commit immediately preceding the code-review fixes). The
verified artifacts and per-branch turn logs are inside the
`harness.verifiedArtifacts` and `harness.trace` fields of that
JSON. The size-20 set's full SMT-LIB (210-sum `(distinct ...)`)
re-verifies cleanly under any modern Z3 (≥ 4.13).
