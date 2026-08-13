# The canonical unwrapping rule: three runs, and what they cost

A follow-on to [phase-unwrapping.md](phase-unwrapping.md). That run answered a
neighbouring question about when the L1 minimum-cost flow is unique. These three
answered a different one: given that it is *not* unique, what should the
unwrapper return instead, and can that thing be computed?

**The setting.** A phase field is measured modulo $2\pi$. Wrapped increments
$d(e) = W(\psi(v) - \psi(u))$ are known on every grid edge; the unknown is the
integer correction $k(e)$ making $d + 2\pi k$ a genuine gradient field. Around
each unit square the signed sum of $k$ equals minus that square's residue, so
$k$ is an integer flow on the dual grid with prescribed divergence. Costantini's
method — what everyone ships — minimises $\sum_e w(e)\,|k(e)|$, a min-cost flow
solved in polynomial time by network simplex. The weights come from a coherence
map, and coherence is *zero* over masked and decorrelated ground, so $w(e) = 0$
genuinely occurs, exactly where the data is worst.

That last fact is the whole difficulty. Zero weights are what make the L1
argmin infinite.

| | gen-17 | gen-18 | gen-19 |
| --- | --- | --- | --- |
| run | `0d0c3560` | `65d50333` | `cd58e618` |
| turns | 523 | 603 | 140 |
| artifacts | 97 | 122 | 32 |
| confirmed / empirical / refuted | 56 / 9 / 0 | 79 / 5 / 2 | 16 / 2 / 0 |
| artifacts refused as unfaithful | — | — | 14 |
| branches shipped / culled | 10 / 5 | 7 / 5 | 1 / 3 |
| recorded failures | 125 | 164 | 58 |
| ended | completed | server died ~20 h in, 3 branches still active | completed in 2 h 25 m |

---

## gen-17: the rule exists

**The result.** Fix any order on the edges. On any instance with a nonempty
feasible set of integer corrections, the following returns exactly one flow:

1. among all feasible $k$, minimise $\sum_e w(e)\,|k(e)|$;
2. among those, minimise $Q(k) = \sum_e k(e)^2$;
3. among those, take the least under the fixed edge order.

Shipped by `B4.2.2.3.2.2` and verified in Lean.

**Why the middle stage is load-bearing.** The obvious rule — minimise L1, then
break ties lexicographically — is not well defined. `B4.2.2.3.3` proved it fails
on an explicit 5-vertex instance whose L1-optimal set is $\{(1,1,n,n) : n \in
\mathbb{Z}\}$: an infinite coset with no least element in the standard edge
order. Lean's `zero_cycle_no_lex_min_std` proves no lexicographic minimum
exists, and `zero_cycle_no_lower_bound` proves that for every integer $M$ some
optimal flow has a coordinate below $M$.

The quadratic stage fixes this for a reason worth stating plainly: a level set
of $Q$ sits inside a sphere in $\mathbb{Z}^E$ and is therefore *finite*. Stage 2
collapses the unbounded directions to finitely many candidates, and only then
does a lexicographic minimum exist. Stages 1 and 2 each need their own argument
(`l1_opt_nonempty`, `quad_stage_finite_nonempty`); stage 3 is Mathlib's
linear-order instance plus "every nonempty finite subset of a linear order has a
unique least element".

**One strengthening, checked by hand.** The Lean development takes weights to be
natural-valued. The argument does not need that: it goes through for real
nonnegative weights, because stage 2's finiteness comes from $Q$ alone and never
from the weights. The shipped statement is therefore weaker than what was
actually proved.

### The supporting structure

**Zero-weight circulations, and a false generalisation.** `B4.2.2.3` gives the
sharp form: if $k$ is feasible and $c$ is an integer circulation supported
*only on zero-weight edges*, then $k + nc$ is feasible and L1-optimal for every
$n$. The natural stronger phrasing — circulations of zero *total signed weight*
$\sum_e w_e c_e = 0$ — is **false**, and the branch refuted it rather than
assuming it: on two disjoint unit-weight 2-cycles, $c = (1,1,-1,-1)$ has total
signed weight zero, yet moves the cost from 0 to 4. The correct condition is
per-edge zero-weight support.

**Tied paths are generic, not a toy symmetry.** `B4.2.2.2` proved that $m$
independent unit-weight two-path dipole gadgets give exactly $2^m$ L1-optimal
flows, via a Lean bijection to $\mathrm{Fin}\,m \to \mathrm{Bool}$. So
non-uniqueness is exponential in the number of independent tied residue pairs.
This matters for gen-18 below.

**A concrete instance.** `B4.3` pinned the uniform 3×3 grid with residues $+1$
at vertices 1, 2 and $-1$ at 8, 9: minimum L1 cost exactly 6, exactly 9 flows
achieving it, every one with edge values in $\{-2,\dots,2\}$. Z3 unsat for the
lower bounds, then two independent exhaustive enumerations (Prolog CLPFD and
Octave) agreeing on the count.

**A negative result.** `B4.2.2.3.2.3` refuted the literal residual-network
criterion in the direction "unique optimum $\Rightarrow$ no zero-cost residual
directed cycle": a single forced unit edge has a unique optimum, yet its
residual network contains the forward/reverse 2-cycle of cost $+1 - 1 = 0$.

All ten shipped answers were re-derived independently before being recorded here.

---

## gen-18: can it be computed?

Seeded from gen-17 and pointed at four questions: **Q-A** polynomial-time
computability of the three-stage rule (or hardness), **Q-B** a checkable
uniqueness criterion, **Q-C** what stage 2 actually costs, **Q-D** recovery
guarantees.

### Q-A — an algorithm, on a class narrower than it looks

`B2` shipped a polynomial-time algorithm for instances with a **unique stage-2
minimizer**. The device is a scalarization: with $C(k) = \sum w_e|k_e|$ and
$Q(k) = \sum k_e^2$, take any $C$-minimizer $k_0$, set $A = Q(k_0) + 1$, and
minimise $A\cdot C + Q$ over flows with coordinates bounded by $D = A\,C(k_0) +
Q(k_0)$. The weight $A$ is large enough that no gain in $Q$ can pay for a loss
in $C$, so a single separable convex-cost flow solves stages 1 and 2 together.
The finite-set scalarization lemma and the coordinate bounds are Lean-verified;
polynomial-time solvability of the two flow subroutines is taken as standard
background rather than reproved.

**The restriction excludes the interesting case, and the answer does not say so
(vf-izq).** Requiring a unique stage-2 minimizer removes precisely the tied-path
mechanism that gen-17 proved is generic:

- the 3×3 instance has $Q$ values $6,6,6,6,6,6,8,8,10$ — a six-way tie at the
  minimum;
- a single dipole gadget has two optima, both with $Q = 2$;
- $m$ gadgets give $2^m$ optima, all with $Q = 2m$.

What survives the restriction is essentially the zero-weight-cycle case, where
$Q$ does separate the coset. So Q-A is answered for the mechanism that stage 2
was invented to handle, and unanswered for the mechanism that made stage 3
necessary. The shipped text states the restriction but does not say what it
costs, which is the gap worth carrying into the next run.

### Q-B — the criterion, corrected and mechanised

`B4.2` found why the literal criterion fails, in one line of algebra: traversing
both residual arcs of an edge changes cost by $w\,(|k+1| + |k-1| - 2|k|)$, which
is $2w$ at $k = 0$ and $0$ everywhere else. So at nonzero flow the same-edge
back-and-forth cycle *always* has zero residual cost while doing nothing to the
flow. Any correct criterion must exclude these trivial 2-cycles. (Verified
independently over $k \in [-1000, 1000]$.)

`B2.3.3` then validated the corrected criterion — unique iff no zero-cost simple
directed residual cycle *other than* a same-edge forward/reverse pair —
exhaustively on two families: all 231 unit-weight directed 4-cycle instances
with residues in $[-3,3]$ summing to zero, by two independent routes; and all
405 feasible 3-vertex digraphs with residues in $\{-1,0,1\}$. In every nonunique
instance *every* optimal flow carries such a cycle; in every unique instance
none does.

`B2.3.3.3` supplied the algebra that would make this checkable in polynomial
time: under nonnegative reduced costs, a zero-cost cycle must be entirely tight
(Lean, arbitrary finite index type — a zero sum of nonnegative integers forces
every summand to zero), reduced costs telescope around a cycle to the original
cost (Z3), and an exact sweep of all 15,625 three-vertex digraphs with arc costs
in $[-2,2]$ found 2,487 with no negative cycle and zero failures of the
tight-subgraph equivalence. Those are the ingredients of the standard check —
optimal flow, optimality potentials, restrict to tight arcs, detect a cycle —
but assembling them into a general theorem is not done.

### Q-C — what stage 2 buys

`B2.3` measured the funnel on the 3×3 instance: **9 L1-minimizers → 6 after the
quadratic stage → 1 after lex**. Stage 2 rejects three flows (two at $Q=8$, one
at $Q=10$) and leaves a six-way tie for stage 3 to settle. Re-derived here over
the cycle space of the grid (12 edges, incidence rank 8, so the four unit
squares span the circulations): cost 6, nine optimal flows, $Q$ distribution
$\{6 \times 6,\ 8 \times 2,\ 10 \times 1\}$.

The branch reports the lex-least flow as
$[-1,-1,0,0,0,0,0,-1,-1,0,-1,-1]$, which matches under its edge order and sign
convention. That vector is *not* reproducible without them — reorder the edges
or flip an orientation and stage 3 returns a different flow. Only the counts are
convention-free, which is itself a fact about the rule: stage 3 is a choice of
presentation, not a property of the instance.

Worth noting what this says about the rule: on the one instance anyone has
measured, stage 3 does most of the disambiguating, not stage 2.

### Q-D — recovery, in the one regime where it is easy

`B4.3` proved a zero-dominance lemma in Lean, for any finite edge set with
nonnegative integer weights: if the zero correction is feasible, every nonzero
feasible $k$ either costs more in L1, or ties L1 and loses on $Q$. Since
$\mathrm{cost}(0) = Q(0) = 0$, the rule returns $k = 0$ and the recovered phase
is exact. The restriction is real — all residues zero, wrapped increments
already a gradient — but the lemma is genuinely universal within it, and it is
the case where L1 alone is *not* enough: on already-consistent data L1 alone
permits e.g. $(0,0,-6,-6)$ along a zero-weight cycle, where stage 2 forces
$(0,0,0,0)$.

---

## gen-19: the tie-break cannot be made canonical

Seeded from gen-18 and pointed at three questions: **Q-1** (main) the
polynomial-time computability of the rule *without* assuming a unique stage-2
minimiser — the gap vf-izq names — **Q-2** whether stage 3 is even the right
tie-break, and **Q-3** how large the stage-2 set gets on grid instances that
look like interferograms rather than adversarial gadgets.

It answered Q-2, measured Q-3, and did not settle Q-1.

### Q-2 — no symmetry-invariant selector exists

**The result, shipped by `B4`.** Take the two-path dipole square: vertices
1..4, edges $(1,2), (2,4), (1,3), (3,4)$, divergence $+1$ at vertex 1 and $-1$
at vertex 4, unit weights. Feasibility forces $k_1 = k_0$, $k_3 = k_2$ and
$k_0 + k_2 = 1$. Minimum L1 cost is 2, and the stage-2 optimal set is exactly

$$S_2 = \{(0,0,1,1),\ (1,1,0,0)\}.$$

The reflection swapping the two paths is an automorphism of the instance — it
preserves feasibility and L1 cost — it exchanges the two optima, and it fixes
neither. An invariant selector would have to return an element of $S_2$ fixed
by every automorphism, and there is none. So **no tie-break that depends only
on the instance up to symmetry can single out one flow.**

Confirmed by Z3 (quantified integer arithmetic, unsat on the negation) and
independently by Octave enumeration over integer flows in $[-10,10]$, with a
passing cross-review. Re-derived here in Python before recording: minimum cost
2, $|S_1| = 2$, $S_2 = S_1$, the reflection is an automorphism of the full
feasible set, maps $S_2$ onto itself, and has no fixed point in it.

**This matters for the engineering, not just the mathematics.** gen-18's Q-C
noted in passing that stage 3 "is a choice of presentation, not a property of
the instance". That is now a theorem rather than an observation: the
dependence on edge order is *forced*. Any canonical unwrapper must break ties
by something extrinsic to the instance — serialisation order, orientation
convention, a tie-break seed — and that is not a defect to be engineered away.
The honest shipping advice is to fix the convention and document it, not to
search for a better rule.

**The strategy was handed over in the problem statement.** The Q-2 prompt said,
in as many words, "prove that no invariant selector exists — for instance by
exhibiting an instance whose symmetry group acts transitively on its stage-2
optimal set, which would make any invariant rule have to choose among genuinely
indistinguishable flows." That is the attack `B4` used. What the run
contributed was finding the smallest witness, characterising its optimal set
exactly, verifying the automorphism and fixed-point-freeness two independent
ways, and scoping the claim correctly. The result is real; it is less
independent than the shipped text reads.

### Q-3 — measured, on 3×3 only

`B4` ran 60 random 3×3 grids with unit weights and random $\pm$ residue pairs.
Stage-2 tie sizes came out $[22, 15, 12, 4, 3, 3, 1, 0, \dots]$ for sizes
$1..20$ — so 22 of the 60 had no tie at all, 38 did, and the largest observed
tie was 7. Ties are the common case even on benign instances, but small ones.
Recorded as `empirical`, and the shipped answer says plainly that this is a
measurement on 3×3 that does not extend to 4×4 or to the adversarial gadgets
where $2^m$ ties are already proven.

Suggestive rather than decisive: it says nothing about how the tie grows with
$n$, which is the question that decides whether Q-1 is theory or practice.

### Q-1 — still open

Unanswered, and the shipped answer leads with that rather than burying it. The
greedy attack the prompt named — fix $k(e_1)$ to its least value consistent
with staying stage-2-optimal, then $k(e_2)$, and so on — was not settled either
way; whether each such step is itself a polynomial feasibility question is the
crux and remains the crux. vf-izq stays open.

### What the harness did, and did not do

Worth recording because it is the point of running these at all.

**The audit gate refused 14 of 32 artifacts as unfaithful** — a 44% refusal
rate, far above gen-17 and gen-18. The refusals are not noise: they are mostly
earlier attempts at the *same* claims that later shipped, rejected because the
encoding did not establish what the claim said. `B2`'s turn-38 SMT artifact
returned **`sat`** and was offered as evidence for a universal statement, which
is precisely the failure mode the gen-14 note names — a correct number is not a
verified one. `B4` needed three tries (turns 22, 24, then 26) before an
encoding of the automorphism argument was accepted. The gate cost turns and
earned them.

**The slow tier was never used.** All 32 artifacts are `fast`. Every SMT
artifact went through plain `z3_check` rather than the dual-encoding
cross-checked path, and all 9 Lean artifacts came from one-shot `lean_check`
rather than interactive tactic mode closing a goal. The `tier-escalation` gate
fired four times to push for exactly that and was ignored four times. So the
"confirmed by Z3 and independently by Octave" in the shipped answer is the
*model* choosing to run two tools, not the harness's own dual-encoding check —
a weaker guarantee than the vocabulary suggests.

**Gate predictions mostly did not come true.** Of 35 firings, 26 settled: 4
met, 22 unmet. Every `milestone` prediction (4/4) and every `tier-escalation`
prediction (4/4) went unmet. The gates are settling their predictions honestly,
which is the mechanism working; what the record shows is that firing a gate
does not reliably change what a branch does next.

**Repopulation, first run in the wild.** The new `:repopulate` gate fired 5
times and settled 1 met, 4 unmet. The one that took (`B2`, turn 19) produced
`B2.2` at turn 22 — the branch that shipped three of the confirmed Lean and SMT
artifacts underpinning the stage-2 classification. So it earned its place, but
on a 1-in-5 hit rate, and the beam's growth to 7 branches is mostly
`branch-out`'s doing, not its own.

**The run was short.** 140 turns against a 300 budget, 39 rounds, done in 2 h
25 m — a fifth of gen-18's length — because `B4` reached `done` and
`stop-on-first-done?` closed the rest. Two branches were culled on consecutive
failures after the Pareto reprieve was spent; three were abandoned as
superseded.

---

## Corrections and caveats

**A shipped answer misdescribes its own graph (vf-6ai).** gen-17's `B4` says
"the 16-vertex/12-edge graph consisting of three disjoint two-path dipole
gadgets". Its own construction builds $s = 4g+1$, $a = 4g+2$, $b = 4g+3$,
$t = 4g+4$ for $g = 0,1,2$ — vertices 1..12. The graph has **twelve** vertices,
and `B4.2.2` describes the same object correctly as 12-vertex/12-edge. The
numeric content (8 feasible flows, all of cost 6) is unaffected and was verified.
Both the audit and the review gate passed the wrong description, which is the
part worth remembering: the gates check that claims match evidence, and a wrong
noun that no engine ever consumed goes straight through.

**Nothing here answers the original question.** No run touched $\sigma$, the
Gaussian field, or the torus. What is still open after three runs: Q-A/Q-1 in
general — the tied case, which is the whole difficulty and which gen-19 left
where it found it (vf-izq) — Q-B as a theorem for arbitrary graphs, Q-3 beyond
3×3 and as a function of $n$, and Q-D with nonzero residues or noise.

**One of the three runs was steered to its answer.** gen-19's Q-2 prompt named
the proof strategy — exhibit an instance whose symmetry group acts transitively
on its stage-2 optimal set — and that is what shipped. Worth holding against
any reading of these runs as evidence about what the harness discovers on its
own: it executed and verified a suggested attack well, which is a different
claim.

**The gates did work, unevenly.** gen-18's `B2` had `done` refused five times
and `B2.3.3` three, in every case for asserting the thesis when the evidence
supported something weaker; gen-19's audit refused 14 of 32 artifacts outright.
The accepted answers are the ones that state their own restrictions, and that
friction is most of why the shipped text above can be read at face value.
Against that: gen-19's gate *predictions* settled 4 met to 22 unmet, and its
`tier-escalation` gate never once got a branch onto the slow tier. Refusal
works; nudging does not.

**gen-18 did not finish.** The server died around 2026-08-10 20:15, roughly 20
hours in, with `B2.2`, `B4.3.2` and `B4.3.3` still active. Its row read
`running` for days afterwards (vf-g2l), which is what prompted
`reconcile-orphans!` — crashed runs are now marked `interrupted` at startup
rather than asserting forever. 603 of 300 max turns is turns across all
branches, not scheduler rounds.
