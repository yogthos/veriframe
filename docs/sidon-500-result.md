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

## Reproduction (Round 1)

The Round-1 trace is from the run on commit
[`fe8e229`](https://github.com/yogthos/veriframe/commit/fe8e229)
(the commit immediately preceding the code-review fixes). The
verified artifacts and per-branch turn logs are inside the
`harness.verifiedArtifacts` and `harness.trace` fields of that
JSON. The size-20 set's full SMT-LIB (210-sum `(distinct ...)`)
re-verifies cleanly under any modern Z3 (≥ 4.13).

---

# Round 2 — 2026-05-02 (later)

After Round 1's analysis, several harness changes were made and the
problem was re-run. This section records the second outcome.

## Summary

Run on commit
[`117356f`](https://github.com/yogthos/veriframe/commit/117356f) with
the following safeguards in place:

- **`review` tool** — mandatory cross-check before `done` when any
  confirmed artifact exists in the branch. Runs an INDEPENDENT
  encoding (different style, different polarity, ideally a
  different tool) and compares verdicts.
- **`MILESTONE_PROMPT` injection** — fired automatically the first
  time a branch lands a confirmed artifact. Tells the model "you
  have a verified result; stop exploring; default to `review` then
  `done`."
- **Cull-protection window** — branches with a confirmed artifact
  in the recent 5 turns are protected from the 3-consecutive-
  failure cull rule.
- **Anti-existential warning + JSON auto-repair** — system-prompt
  guidance against asking Z3 to find combinatorial witnesses, plus
  a control-character repair pass on tool-call JSON.

Outcome: **the harness completed successfully for the first time on
this problem.** Branch B3 produced a Mian–Chowla 20 confirmation,
received the milestone prompt, ran `review` with an independent
encoding, got REVIEW PASSED, and called `done`. Wall-clock 24 min,
36 turns. Final answer:

$$
S = \{1, 2, 4, 8, 13, 21, 31, 45, 66, 81, 97, 123, 148, 182, 204, 252, 290, 361, 401, 475\}.
$$

## Cross-check (the new soundness guarantee)

The result is verified by **two independent SMT-LIB encodings that
agree**:

1. **Existence-of-collision** (the `review` cross-check): assert
   $\exists\,a, b, c, d \in S$ with $a < b$, $c < d$, $(a, b) \neq
   (c, d)$, $a + b = c + d$. Z3 returned **UNSAT** — no such
   collision exists, so $S$ is Sidon.

2. **Distinct-sums** (the original verification): assert
   `(distinct (+ a_i a_j) ...)` enumerating all $\binom{20+1}{2} =
   210$ pair sums. Z3 returned **SAT** — all sums are distinct
   under the fixed assignment, so $S$ is Sidon.

The encodings are independent in the sense that demands by the
problem ("are pair sums distinct?") and the encoding's assertion
("does a collision exist?" vs. "are all sums distinct?") have
opposite polarities. A bug in one encoding (e.g., the size-26
`forall`-with-narrow-chain bug from Round 1) would not also appear
in the other. Both agreeing is strong evidence the set is genuinely
Sidon.

## Beam summary

| Branch | Turns | Status | Notes                                                                                  |
|--------|-------|--------|----------------------------------------------------------------------------------------|
| B1     | 8     | culled | 3 consecutive failures, no recent confirmed work                                       |
| B2     | 4     | culled | same                                                                                   |
| B3     | 15    | **DONE** | Mian-Chowla 20 → milestone prompt → `review` → `done`. 13 verified artifacts.         |
| B4     | 4     | culled | same                                                                                   |
| B5     | 5     | culled | same                                                                                   |

Of B3's 13 artifacts: the shipped Mian–Chowla 20, the cross-check
artifact, plus 11 refuted "can we add an element X?" attempts. The
refuted attempts demonstrate the harness honestly rejecting bad
extensions (e.g., $S \cup \{490\}$ has a collision; $S \cup \{3\}$
has a collision; etc.) — the verifier is correctly catching the
model's wrong guesses.

## A new false-positive surfaced (and the harness fix)

In B3's trace there is a fourth-from-last "confirmed" artifact:

```
claim: "There exists a Sidon set of size 23 in {1, …, 500}."
verdict: sat
witness model: a1 = 0, a2 = 0, a23 = 0
smtlib (excerpt):
  (declare-const a1 Int) (declare-const a2 Int) ... (declare-const a23 Int)
  (assert (and (>= a1 1) (<= a1 500) ... (>= a23 1) (<= a23 500)))
  (assert (distinct a1 a2 ... a23))
```

The model used **literal `...` ellipsis as shorthand** for the
remaining 21 declarations and 21 assertions. That's not a valid
SMT-LIB construct. Z3's actual response on this input:

```
(error "line 1 column 47: invalid command, '(' expected")
(error "line 2 column 57: unknown constant ...")
(error "line 3 column 24: unknown constant ...")
(error "line 5 column 28: unknown constant a3")
sat
( (define-fun a2 () Int 0) (define-fun a1 () Int 0) ... )
```

Z3 emitted **four parse errors**, then output `sat` because the
constraint set that survived parsing was effectively empty —
trivially satisfiable with default zero values. The harness's
`runSmt` was looking for the last `sat`/`unsat`/`unknown` line and
ignoring the `(error ...)` lines preceding it, so the artifact got
recorded as `claimStatus: confirmed`.

The shipped answer was unaffected (B3 cross-checked and shipped
Mian–Chowla 20, not this bogus size-23 claim), but the false
positive lived on in the trace and would have polluted any
future-work consumer of the JSON.

**Fix landed in commit
[`117356f`](https://github.com/yogthos/veriframe/commit/117356f)
and tightened in the follow-up commit**: `runSmt` now refuses to
report a verdict whenever Z3 emitted any `(error ...)` line in
stdout, except for the benign `model is not available` message
that follows `(get-model)` after UNSAT. New tests in
`tests/smt.test.ts` cover the ellipsis-shorthand pattern and the
undeclared-symbol pattern.

## Lessons (updated)

1. **Cross-checking works.** The `review` tool, with an enforced
   independent encoding before `done`, is the correct shape for
   defending against encoding bugs. Two SMT-LIB encodings agreeing
   is strong evidence; they don't both miss the same edge case
   unless the model's reasoning is consistently wrong about the
   property.

2. **Z3 error lines must be respected.** Earlier the harness only
   parsed verdicts and ignored errors. Z3 keeps running after parse
   errors and will SAT whatever remained. Now the harness refuses
   to interpret the verdict whenever errors were emitted.

3. **The model's encoding-correctness gaps will keep surfacing.**
   Round 1 had `forall`-quantified ordering chains. Round 2 had
   ellipsis-shorthand SMT-LIB. Future rounds will surface other
   variants. The cross-check + error-line strictness combination
   defends against most; per-claim cross-checking with a
   harness-supplied template would defend against more (TODO).

4. **The milestone prompt was load-bearing.** Without it, the model
   spent the prior run pushing for size 21+ instead of shipping
   the verified size 20. Inserting a user-message-level
   intervention at the moment of first confirmation flipped the
   behaviour to the correct one.

## Reproduction (Round 2)

Run on commit
[`117356f`](https://github.com/yogthos/veriframe/commit/117356f),
problem `open-sidon-set-500`, max 60 turns. The trace is at
`/tmp/agent-open-sidon-set-500.json` after the Round 2 run; the
shipped answer can be re-verified independently with the
`(distinct sums)` encoding (210 pair sums) — Z3 returns `sat`
under any modern build (≥ 4.13).
