# Erdős–Straus, $n \equiv 1 \pmod 4$ — Cross-Disciplinary Creativity Experiment

## What we set out to test

Erdős–Straus had been formally verified for $n \equiv 0, 2, 3 \pmod 4$
in a prior run (see `docs/erdos-straus-result.md`). The remaining
case $n \equiv 1 \pmod 4$ is the historically hardest residue
class — 78 years of standard number-theoretic techniques have made
sub-residue progress without closing the full class.

The experiment: **can the harness elicit a genuinely novel
cross-disciplinary approach** when the prompt explicitly
prohibits standard techniques and lists alternative angles
(physics, computer science, information theory, topology,
algebraic geometry, game theory, logic, recent breakthroughs
like Polynomial Freiman-Ruzsa via entropy methods or cap-set via
the polynomial method)?

Setup: 100-turn budget, beam width 5 with per-branch base
temperatures 0.5 → 1.3, full defense stack (done-gate,
existential detection, sorry rejection, audit/review,
templates).

## What actually happened

The run terminated by beam exhaustion (no `done()` called) after
**150 minutes and 207 total turns**. 59 verified artifacts:

| Status | Count | Meaning |
|---|---|---|
| confirmed | 16 | sound concrete or parametric results |
| **existential** | **33** | Z3 said "yes, a solution exists" without pinning a witness |
| refuted | 10 | model attempts that didn't survive verification |

**The cross-disciplinary push didn't materialise.** Every verified
result the model produced was a classical sub-residue
construction. None of the suggested cross-disciplinary angles
(statistical mechanics, Kolmogorov complexity, sheaf cohomology,
PFR-style entropy, etc.) yielded a verified artifact.

## The actual mathematical results (such as they are)

Two parametric sub-residue constructions, both classical:

### Sub-result A — covers all $n \equiv 5 \pmod{12}$

For $n = 12t + 5$:
$$
\frac{4}{n} \;=\; \frac{1}{3t+2} + \frac{1}{(t+1)(12t+5)} + \frac{1}{(3t+2)(t+1)(12t+5)}
$$

This is the Schinzel/Webb-style $x = (n+3)/4$ identity. Setting
$z = xy$ collapses the sum to $(n+3)/(n(3t+2))$, which equals
$4/n$ exactly when $n = 12t + 5$.

*Sanity check at $t = 0$ ($n = 5$)*: $(2, 5, 10)$,
$\tfrac{1}{2} + \tfrac{1}{5} + \tfrac{1}{10} = \tfrac{5+2+1}{10} = \tfrac{8}{10} = \tfrac{4}{5}$ ✓

### Sub-result B — covers all $n \equiv 5 \pmod{8}$

For $n = 8k + 5$:
$$
\frac{4}{n} \;=\; \frac{1}{2(k+1)} + \frac{1}{n(k+1)} + \frac{1}{2n(k+1)}
$$

Same trick with $z = 2y$. Setting $x = 2(k+1)$ collapses the sum
to $(n+3)/(2n(k+1))$, which equals $4/n$ when $n = 8k + 5$.

*Sanity check at $k = 1$ ($n = 13$)*: $(4, 26, 52)$,
$\tfrac{1}{4} + \tfrac{1}{26} + \tfrac{1}{52} = \tfrac{13+2+1}{52} = \tfrac{16}{52} = \tfrac{4}{13}$ ✓

### Coverage within $n \equiv 1 \pmod 4$

| Sub-residue | Covered? |
|---|---|
| $n \equiv 5 \pmod{12}$ (i.e., $n \equiv 5 \pmod 4$ ∩ $\not\equiv 0 \pmod 3$ in part) | ✓ Sub-result A |
| $n \equiv 5 \pmod{8}$ (i.e., $n \equiv 1 \pmod 4$ ∩ $\equiv 5 \pmod 8$) | ✓ Sub-result B |
| Residual ($n \equiv 1 \pmod{24}$ and a few other sparse classes) | OPEN |

This still leaves the bulk of the historically-hardest material
open. The model **didn't crack new ground**; it reproduced known
partial results.

## Was anything novel?

**Mathematically: no.** Both parametric constructions are
variations on the same well-known trick (pick $x$ as a small
divisor of $n + 3$; set $y$ and $z$ as products that telescope).
Mordell, Webb, and Schinzel covered these residue classes
decades ago. The conjecture remains open precisely because this
trick stops working at the residual sub-residues.

**Formally: marginally.** These specific Lean theorems
(parametric in $t$ or $k$, with polynomial identities discharged
by `nlinarith`) don't exist in Mathlib. As Lean artifacts they're
new; as mathematics they're textbook.

So: **no, the run produced no novel mathematics.** It reproduced
known results in formal-verification form.

## Why the cross-disciplinary push failed

Several observations:

1. **The verification gate is a Schelling fence around the
   conventional.** Anything formalisable in Lean tends to be
   conventional math. Anything genuinely cross-disciplinary
   (statistical mechanics on integers, Kolmogorov bounds,
   sheaf-theoretic obstructions) is *hard* to formalise in Lean
   — and the harness rewards what verifies. The verification
   machinery is at war with the prompt's invitation to risk.

2. **High temperature didn't translate to creativity.** B5
   (temperature 1.3, the wildest branch) was **culled in 3
   turns** — its initial attempts produced unverifiable Lean
   noise. Meanwhile B1 (temperature 0.5, the most conservative)
   ran the full 100-turn budget and produced both meaningful
   artifacts. **High temperature is at odds with verifiability**
   when the verification system is strict. We got noise from the
   wild branches, not creativity.

3. **Existential pseudo-confirms dominated.** 33 of 59 artifacts
   were existential (Z3 "found" something for specific $n$
   without the model extracting the witness). The model spent
   half the run on existence queries that don't ship. Even with
   the new existential bucket flagging these, the model's
   *behavior* didn't internalise it — it kept asking Z3 for
   existence of solutions to specific instances.

4. **The model recognised what it could verify and stuck to
   that.** Cross-disciplinary framings (the prompt's whole point)
   would have required articulating ideas that don't yet have
   Lean tactics. The model implicitly preferred "I can
   `nlinarith`-close this polynomial identity" over "let me
   sketch a sheaf-cohomological obstruction."

## What this run reveals about the harness's design

This is a meaningful **negative finding** about the system's
interaction with creativity:

- Our cumulative defense stack (no false ships, no `sorry`
  shortcuts, no existential pseudo-confirms, done-gate
  substantiation) is excellent for **conservative confirmation**
  — verifying claims rigorously.
- It is **structurally biased against speculative novelty.** A
  model trying a cross-disciplinary approach has nowhere to
  shelter the speculation; if it can't be formalised within the
  beam-search budget, it gets culled.
- The two run modes (cap-set's bulletproof template ship; here's
  exploration without progress) sit at opposite ends of a
  trade-off the harness doesn't expose.

If we wanted to actually elicit cross-disciplinary thinking, we
would need to reframe the trade-off. Possibilities:

- **A `framing` tool** that records a structural hypothesis
  without requiring formal proof, with the explicit
  understanding that the artifact is a research note, not a
  verified theorem. The done-gate would treat these as
  meaningful but not as substitutes for verified claims.
- **Branch-specific prompts** — high-temp branches get prompted
  to produce framings; low-temp branches get prompted to
  formalise. Beam diversity along the
  speculate-vs-formalise axis, not just temperature.
- **Acceptance of "exploration runs"** as a distinct mode —
  expected to produce framings and partial sketches rather than
  shipped theorems.

But these would change the system's character substantially.
The current harness is a **rigorous-verification machine**.
That's its job. Asking it to also be a creativity engine is
asking for two contradictory things from the same machine.

## Comparison to the prior Erdős–Straus run

| Run | Coverage achieved | Novelty | Honest? |
|---|---|---|---|
| `erdos-straus-general` | $n \equiv 0, 2, 3 \pmod 4$ formally proven | classical constructions | ✓ shipped honestly |
| `erdos-straus-mod1-creative` | $n \equiv 5 \pmod{12}$ + $n \equiv 5 \pmod 8$ formally proven | classical constructions | failed to ship; artifacts honest |

Both runs reproduced classical results in Lean. The first
shipped them via `done()`; this one didn't. Neither produced
novel mathematics. The cross-disciplinary push was wishful
thinking on our part.

## Honest conclusion

We set up an experiment to elicit novel approaches to a
78-year-old open problem. **The experiment failed in its
explicit goal** — no novel mathematics was produced. **It
succeeded as a stress test** — it surfaced that the harness's
defense stack creates strong conservative bias, that
high-temperature branches don't produce verifiable creativity,
and that "verification" and "exploration" are fundamentally
different modes that need different scaffolding.

The two parametric sub-residue Lean theorems are real
contributions to the formalisation of Erdős–Straus partial
results — but to be clear, they're contributions because *no
one's bothered to formalise them in Lean*, not because they're
new mathematics.

## Reproduction

Run on commit
[`56ad981`](https://github.com/yogthos/veriframe/commit/56ad981)
of `main`, problem `erdos-straus-mod1-creative`, max 100 turns,
`HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`. Trace at
`/tmp/agent-erdos-straus-mod1-creative.json`. Each Lean snippet
re-checks cleanly under Lean 4 + Mathlib.

The Lean source for both parametric theorems is in artifacts #7
and #39 of the trace JSON; here is artifact #39's wrapper for
clarity:

```lean
import Mathlib

theorem erdos_8k5 (k : ℕ) :
    let n := 8 * k + 5
    let x := 2 * (k + 1)
    let y := n * (k + 1)
    let z := 2 * n * (k + 1)
    4 * x * y * z = n * (x * y + x * z + y * z) := by
  intro n x y z
  unfold_let
  ring
```

(Lean accepts; `ring` discharges the identity directly.)
