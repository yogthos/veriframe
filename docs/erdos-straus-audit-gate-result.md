# Erdős–Straus, Residual Primes — Audit-Gate Forced Honest Shipping

## Summary

Same problem (`erdos-straus-residual-primes`), same model
(`deepseek-reasoner`) — but with the new **mandatory pre-done
audit** with thesis-vs-problem reflection. The previous run on
this problem shipped a misframed "counterexample to ArXiv
2511.07465" claim. **This run shipped 17 honestly-scoped
Lean-verified prime decompositions instead** — same model,
same prompt, different harness.

The audit gate fired **18 times** and **blocked 7 of 8 done
attempts** until the model produced an answer the auditor
approved. B5 won by being explicit about scope and disavowing
overclaims.

55 minutes wall-clock, 320 turns aggregated, 73 verified
artifacts (38 confirmed, 25 refuted, 10 existential).

## What got shipped (B5's final answer)

> "(1) re-verification of all 4 starter theorems
> (`even_case`, `mordell_3mod4`, `solution_scale`,
> `hasse_reduction`); (2) verification of the ED2 identity
> $(4b-1)(4c-1) = 4p\delta + 1$ from arXiv 2511.07465 for all
> 17 primes $p \equiv 1 \pmod 4$ listed in the problem ($p=5$
> through $p=157$); (3) verification of explicit 3-term
> Egyptian fraction decompositions $\frac{4}{p} = \frac{1}{x}
> + \frac{1}{y} + \frac{1}{z}$ for all 17 primes, each
> checked by Lean's `native_decide`. **No universal claim
> about all primes is made — this is an exhaustive formal
> verification of the concrete instances specified in the
> problem.**"

The shipped table (each row a Lean theorem of shape `theorem
foo : IsSolution p := ⟨x, y, z, ..., by norm_num⟩`):

| $p$ | $(x, y, z)$ |
|---|---|
| $5$ | $(2, 5, 10)$ |
| $13$ | $(4, 26, 52)$ |
| $17$ | $(5, 34, 170)$ |
| $29$ | $(10, 29, 290)$ |
| $37$ | $(10, 148, 740)$ |
| $41$ | $(11, 164, 1804)$ |
| $53$ | $(14, 265, 3710)$ |
| $61$ | $(16, 366, 2928)$ |
| $73$ | $(20, 292, 730)$ |
| $89$ | $(24, 534, 712)$ |
| $97$ | $(25, 970, 4850)$ |
| $101$ | $(26, 1313, 2626)$ |
| $109$ | $(28, 1526, 3052)$ |
| $113$ | $(30, 678, 1695)$ |
| $137$ | $(35, 2740, 3836)$ |
| $149$ | $(38, 5662, 2831)$ |
| $157$ | $(40, 6280, 3140)$ |

Each is Lean-verified. *Sanity check at $p = 37$*:
$\frac{1}{10} + \frac{1}{148} + \frac{1}{740} = \frac{74 + 5 + 1}{740} = \frac{80}{740} = \frac{4}{37}$ ✓.

## The audit gate caught everything we hoped it would

Sample auditor messages from the run (each is from a real
audit call where the gate blocked a `done` attempt):

> **AUDIT FAILED: C2 — "answer addresses small primes only,
> problem asked for all listed primes."** The problem
> explicitly requests concrete witnesses for each prime in
> the list {5, 13, 17, 29, …, 157}.

> **AUDIT FAILED: A1 — Z3 encoding is contradictory** —
> asserts both `3 | (n+1)` and `3 | (2n+1)` simultaneously,
> impossible for any integer $n$. This makes the UNSAT
> result vacuous.

> **AUDIT FAILED:** "claims to have verified universal ED2
> identity via Z3, but the described SMT encoding (∀ over
> primes) is not actually implementable in Z3 as stated."

> **AUDIT FAILED:** "fails to re-verify three of the four
> required Lean theorems (only `even_case` is touched, and
> even then only by `#check` on three sample values — not a
> proof-term inspection or full validation)."

> **AUDIT FAILED:** "claims a new Lean-formalized theorem
> but provides no Lean code — the only artifact shown is a
> trivial Z3 script that checks a single known solution, not
> a general verification."

The auditor cited the checklist categories (A1 encoding
soundness, B1 verdict-vs-answer, C2 thesis-vs-problem) by
label, named the specific failure pattern, and recommended
the concrete fix. **It enforced honest scope.**

The one audit that PASSED (B3, mid-run):

> **AUDIT PASSED.** "The answer accurately reports
> Z3-verified decompositions for seven specific primes
> $p \equiv 1 \pmod 4$, each triple passing the direct
> arithmetic check; the encoding of that check is sound
> (negation yields UNSAT). The answer's scope and the
> artifacts agree."

## Tool histogram + per-branch behavior

| Branch | Turns | Status | Notes |
|---|---|---|---|
| B1 | 95 | abandoned | high productivity, contributed many Lean theorems |
| B2 | 59 | culled | repeatedly failed audit, eventually ran out of options |
| B3 | 66 | culled | one audit passed (mid-run) but later attempts failed |
| B4 | 5 | culled | early dead end |
| **B5** | **95** | **DONE** | shipped honestly after carefully scoped audit-passed answer |

Tools: 18 audit, 8 done attempts, 58 verify_smt, 56 verify
(Prolog), 40 lean_define, 36 verify_lean, 30 proof_step.

## Why this matters

The previous run on this same problem (no audit gate) shipped:

> **"Counterexample to ArXiv 2511.07465 (2025) for p=5"**

That claim was wrong: the model had tested a guessed mapping
$(x, y, z) = (4b-1, 4c-1, \delta)$, gotten Z3 UNSAT, and
declared the preprint refuted. The actual ED2 mapping is
$A = bc/\delta$, which works for $p = 5$ (gives the classical
$(2, 5, 10)$). All the model's Z3 artifacts were correct;
only the *high-level claim* was wrong. The original done-gate
(token substantiation) passed because the answer mentioned
"p=5" and "UNSAT" — verified tokens.

This run, with the same prompt and same model, shipped 17
honestly-scoped Lean-verified prime decompositions. **Same
artifacts could have been shipped — the audit gate prevented
the misframing.**

## How the audit gate works

When `done(answer)` is called, the harness checks:

1. *(existing)* Confirmed artifacts present → require
   `lastReview` (encoding cross-check)
2. *(existing)* Answer must reference distinctive tokens
   from the most recent confirmed artifacts
3. *(NEW)* Confirmed artifacts present → require
   `lastAudit` with `passed = true`
4. *(NEW)* Audit's `proposedAnswer` must substantively match
   the `done()` answer (loose token-overlap check)

The audit tool now requires a `proposedAnswer` parameter (the
prose the model intends to ship) and runs a sub-LLM auditor
with three categories of checks:

- **A. Encoding soundness** — vacuous SAT, missing
  distinctness, quantifier scope, polarity, witness sanity
- **B. Verdict-vs-answer alignment** — does the prose
  accurately describe what the engine actually verified?
  Catches "I'm reading Z3's response wrong."
- **C. Thesis-vs-problem reflection** — re-read the original
  problem; does the proposed answer address it, or a
  different (possibly easier) related question?

If audit passes, `done()` proceeds (with the matching
proposedAnswer). If audit fails, the audited artifact is
downgraded to `refuted` and the model must address the
auditor's specific concern.

## Honest novelty assessment

**Mathematically novel?** No. The 17 decompositions are
classical; each prime $p \equiv 1 \pmod 4$ in the tested list
has a long-known Erdős–Straus solution. Salez's 2014
verification covered all such primes up to $10^{17}$.

**Formally novel?** Yes, modestly. The 17 specific Lean
theorems aren't in Mathlib. Combined with the prior verified
results, the harness has now produced a Lean-formalized
foundation covering substantial parts of Erdős–Straus's
known partial-result literature.

**Strategically meaningful?** Yes. The harness now has a
verified set of explicit constructions for the residual
primes, which can be used as anchors for future structural
generalisations.

**Process-wise meaningful?** **Most importantly yes.** The
audit gate transformed the harness's reliability profile.
Previously the model could ship a misframed conclusion about
correctly-verified artifacts; now it can't. The audit forces
either honest scoping or refusal-to-ship.

## Reproduction

Run on commit
[`0d86964`](https://github.com/yogthos/veriframe/commit/0d86964)
of `main`, problem `erdos-straus-residual-primes`, max 100
turns, `HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`.
Trace at `/tmp/agent-erdos-straus-residual-primes.json`. Each
of the 17 Lean theorems re-checks under Lean 4 + Mathlib.

## What this surfaces about the harness

The defense stack is now seven layers deep:

1. Encoding lint (pre-execution)
2. Witness sanity check (post-SAT)
3. `verify_template` cross-encoding
4. `review` model-supplied independent cross-check
5. Done-gate token substantiation
6. **Pre-done audit (NEW)** — encoding + answer + thesis
7. Audit's `proposedAnswer` must match `done()`'s answer

To ship a misframed claim, the model would need to convince
the audit sub-LLM that the misframing is honest — which the
auditor's checklist specifically watches for. The harness is
now structurally biased toward **honest narrow shipping** over
**tempting broad over-claims**.

This trade-off has a cost — the model has to think harder
about what it actually verified — but the cost is small (B5
shipped in 95 turns) compared to the reliability gain (no
false claims surviving to the user).
