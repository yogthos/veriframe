# Erdős–Straus, General-Proof Run — Structural Thesis Gate

## Summary

Same problem (general proof of Erdős–Straus for all primes
$p \equiv 1 \pmod 4$), same model (`deepseek-reasoner`), same
audit gate as the prior run — but with a new **mandatory
structural-thesis tool** that requires the model to commit to a
proof skeleton (goal, sub-claims, technique, non-finite
justification) before any verification work toward the goal can
fire. The audit tool refuses to run without a thesis, and now
includes **Check D (thesis-vs-artifact alignment)** that
penalizes shipping finite-instance verifications as a general
proof.

The previous run (no thesis gate) ended at 65 turns with **26
verified artifacts, all small-prime instance verifications, and
zero `audit` calls** — the model never produced a structural
argument worth auditing.

This run shipped a verified counterexample to a sub-claim of
arXiv 2511.07465 (Nov 2025), audit-gate approved.

## What got shipped (B5's final answer, audit-passed)

> "I verified the following claims using Z3. Claim (direct
> existence check): 'There do NOT exist positive integers $b,c$
> satisfying $4bc - b - c = 13$.' — Z3 returned UNSAT,
> confirming that no such $b,c$ exist. This was cross-checked
> with an independent encoding of the equivalent factorization
> $(4b-1)(4c-1) = 53$, which also returned UNSAT. Consequently,
> $p = 13$ is a verified counterexample to the arXiv 2511.07465
> claim that every prime $p \equiv 1 \pmod 4$ can be represented
> as $p = 4bc - b - c$ for positive integers $b,c$. The
> parametric solution $(x, y, z) = (bc, bp, cp)$ therefore does
> not provide a solution for $p = 13$, disproving the preprint's
> universal constructive claim. This does NOT prove the
> Erdős–Straus conjecture; it eliminates a false approach, which
> the problem describes as legitimate progress."

The verified artifacts (all Z3 UNSAT, both encodings agree):

| Encoding | Claim |
|---|---|
| Direct existence | $4bc - b - c = 13$, $b, c \geq 1$ — UNSAT |
| Factorization cross-check | $(4b - 1)(4c - 1) = 53$, $b, c \geq 1$ — UNSAT |

Sanity check: $53$ is prime, so the only positive factorization
is $1 \cdot 53$, requiring $4b - 1 = 1 \Rightarrow b = 1/2 \notin \mathbb{Z}$.
The UNSAT verdict is correct.

## The structural-thesis gate did its job

The new `thesis` tool requires the model to commit, **before any
verification toward the goal**, to:

- **goal** — the universal statement intended to be proved
- **subClaims** — the proof skeleton, decomposed into formally
  verifiable steps
- **technique** — the proof framework chosen
- **nonFiniteJustification** — explicit reason why the approach
  scales to the infinite class

The audit gate refuses to run without a thesis. The auditor's
new **Check D** cross-references the thesis against the verified
artifact: if the thesis is universal but the artifact is
instance-only, audit fails unless the answer explicitly scopes
to the verified instances.

### Per-branch thesis activity

| Branch | Thesis calls | Audit calls | Done | Notes |
|---|---|---|---|---|
| B1 | 1 (turn 18) | 0 | — | Sum-of-two-squares attack; abandoned |
| B3 | 1 (turn 11) | 0 | — | Polynomial identity over $a^2+b^2$; abandoned |
| B4 | 2 (turns 5, 26) | 0 | — | Refined from constructive to disproof; superseded |
| **B5** | **3** | **3** | **✓** | Refined thesis as it learned what was tractable; shipped |

B5's thesis evolution is the interesting part:
1. **Turn 12**: "Show that the ED2 parametric family
   ($p = 4bc - b - c$) cannot cover all primes $p \equiv 1
   \pmod 4$ by establishing that $p = 13$ is a counterexample,
   thereby refuting a key claim of arXiv 2511.07465."
2. **Turn 23**: refined to explicitly target the disproof
   thesis after a verify_smt confirmed UNSAT.
3. Audit at turn 26 PASSED — auditor cross-checked encoding,
   verdict-vs-answer, problem-vs-thesis, and artifact-vs-thesis.
4. Done at turn 27 was BLOCKED by the substantiation
   token-check (answer didn't reference the artifact's
   distinctive identifiers).
5. Done at turn 28 with refined wording succeeded.

The sequence demonstrates the gate behaving as designed: model
commits to a structural plan, refines as it learns, ships only
what passes both audit and substantiation.

## Tool histogram

| Tool | Count |
|---|---|
| `__no_call__` | 104 |
| `verify_smt` | 20 |
| `thesis` | 6 |
| `audit` | 3 |
| `done` | 2 (1 blocked, 1 success) |
| `lean_define` | 2 |
| `verify_lean` | 1 |
| `verify` | 1 |
| `review` | 1 |
| `give_up` | 0 |
| **Total steps** | **140** |

Across 5 branches × 28 turns each. The 104 `__no_call__` events
(74% of steps) are concerning — the model often emits reasoning
prose without a tool call. The harness recovers (re-prompts with
"emit one tool call per turn") but burns budget.

## Comparison to the prior (no-thesis-gate) run

| Metric | Prior run | This run |
|---|---|---|
| Steps | 65 | 140 |
| Status | Beam exhausted | DONE |
| `thesis` calls | n/a (tool didn't exist) | 6 |
| `audit` calls | **0** | **3** |
| `done` calls | 0 | 2 (1 success) |
| `verify_smt` calls | 56 | 20 |
| Verified artifacts | 26 (all instance-only) | 5 confirmed |
| Shipped result | None | δ=1 case of arXiv 2511.07465 disproved for p=13 |

The thesis gate reduced the volume of low-value instance
verifications (verify_smt: 56 → 20) and channeled the model
toward a structural objective. The resulting shipped artifact
is small but real, and was framed honestly.

## Honest scope assessment

**What was actually disproved**: the **$\delta = 1$
specialization** of arXiv 2511.07465's ED2 method for $p = 13$.
That is, the equation $4bc - b - c = 13$ (equivalently
$(4b-1)(4c-1) = 53$) has no positive integer solutions.

**What was NOT disproved**: the preprint's full ED2 method,
which is **$\delta$-parameterized**:
$(4b-1)(4c-1) = 4P\delta + 1$ for *some* positive integer
$\delta$. The model only checked $\delta = 1$.

For $p = 13$ with $\delta = 2$:
$(4b - 1)(4c - 1) = 105 = 3 \cdot 35 = 7 \cdot 15$. The
factorization $(7, 15)$ gives $(b, c) = (2, 4)$, and the
preprint's mapping $A = bc/\delta = 8/2 = 4$ is a positive
integer — yielding a candidate Erdős–Straus solution path. So
$p = 13$ is solvable via ED2 for $\delta = 2$.

**The audit didn't catch this** because the auditor doesn't
have access to the preprint and was asked to evaluate "is the
answer's claim consistent with the verified artifact" rather
than "is the answer's claim consistent with the literature
catalog." Domain-specific over-claims like "I disproved a
preprint by testing one specialization" need either (a) an
auditor with literature access, or (b) the prompt to enumerate
the preprint's full scope explicitly.

## What worked, what didn't

**Worked:**
- Thesis gate forced structural framing — model committed to
  a proof skeleton before chasing verifications.
- `audit` gate (already existed) was actually invoked
  (3 calls vs 0 in the prior run).
- `done` substantiation check caught a first-attempt
  mismatched answer; model recovered with a refined version.
- The shipped framing is honest about not proving the conjecture.

**Didn't work as well:**
- 74% `__no_call__` rate — the model spent most of its
  budget reasoning out loud without committing to a tool
  call. The thesis tool may have inadvertently encouraged
  more prose-heavy turns.
- Auditor lacks literature awareness — can't catch
  "disproved a sub-claim of a preprint, framed as disproving
  the preprint" without the preprint's exact scope being
  enumerated in the prompt.

## Reproduction

Run on commit
[`ec44db2`](https://github.com/yogthos/veriframe/commit/ec44db2)
of `main`, problem `erdos-straus-residual-primes-proof`, max 100
turns per branch, `HARNESS_PROVIDER=deepseek` with
`deepseek-reasoner`. Trace at
`/tmp/agent-erdos-straus-residual-primes-proof.json`. Both Z3
encodings re-check under any modern Z3 (≥ 4.13).

## What this run unlocks for the next one

The δ=1 disproof for p=13 is the entry point for a sharper
follow-up: can the model expand the disproof to the
$\delta$-parameterized general method?

For each prime $p \equiv 1 \pmod 4$, the general ED2 method
requires existence of a positive integer $\delta$ such that
$4P\delta + 1$ admits a factorization $(4b-1)(4c-1)$ with both
factors $\equiv 3 \pmod 4$ AND $bc/\delta \in \mathbb{Z}$. The
δ=1 case is just the first cell. The harness's next run should
test this fuller claim — either confirming ED2 for the primes
where the prior runs verified instance solutions (5, 13, 17, …),
or surfacing a prime where ED2 fundamentally fails for all δ in
some bounded range.

That's the natural follow-up.
