# The canonical unwrapping rule: four runs, and what they cost

A follow-on to [phase-unwrapping.md](phase-unwrapping.md). That run answered a
neighbouring question about when the L1 minimum-cost flow is unique. These four
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

| | gen-17 | gen-18 | gen-19 | gen-20 |
| --- | --- | --- | --- | --- |
| run | `0d0c3560` | `65d50333` | `cd58e618` | `36bf3163` |
| turns | 523 | 603 | 140 | 197 |
| artifacts | 97 | 122 | 32 | 13 |
| confirmed / empirical / refuted | 56 / 9 / 0 | 79 / 5 / 2 | 16 / 2 / 0 | 11 / 0 / 0 |
| artifacts refused as unfaithful | — | — | 14 | 2 |
| confirmed on the **slow** tier | — | — | 0 of 16 | **11 of 11** |
| branches shipped / culled | 10 / 5 | 7 / 5 | 1 / 3 | 1 / 3 |
| recorded failures | 125 | 164 | 58 | 57 |
| ended | completed | server died ~20 h in, 3 branches still active | completed in 2 h 25 m | completed; crashed once at turn 164 and resumed |

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

## gen-20: the honest answer

Pointed at Q-1 alone — the main question, the one gen-19 left where it found
it: give a polynomial-time algorithm computing the three-stage rule on an
arbitrary instance, with no assumption that the stage-2 minimiser is unique,
or prove none exists under a standard complexity assumption.

**The formulation named no attack, deliberately.** gen-19's prompt had handed
over its proof strategy and got it back as the answer, which told us the
harness executes a suggested attack well and nothing about whether it finds
one. gen-19's prompt had also called greedy edge-by-edge fixing "the obvious
attack". Both were removed from gen-20's statement and their absence checked
before launch. What was kept was facts — the $2^m$ tie, the existing
scalarisation algorithm and exactly what its hypothesis costs, the forced
edge-order dependence — so no turns went on reproving them.

It also carried an evidence standard: the load-bearing claim had to be
confirmed by `verify_template` or a closed `proof_start`/`proof_step`, not by
`verify_smt`/`verify_lean`/`verify_octave` alone.

### The withheld attack was rediscovered

At turn 18, `B1` shipped `seq_lower_bound_is_lexmin`: for a finite $T$ and
predicate $P$, if $c$ satisfies $P$ and at each coordinate $i$ is a lower bound
among all $P$-satisfying elements agreeing with $c$ on coordinates $< i$, then
$c$ is the lexicographic minimum of the $P$-satisfying subset. That is exactly
the greedy coordinate-by-coordinate attack the statement had withheld.

So: gen-19 was handed its attack and executed it; gen-20 was handed nothing and
found the same one. For an attack this natural, the harness does the finding.

### What shipped

`B3`, at turn 197, with `done` accepted **first try and no refusals** — the
answer was already correctly scoped when it was offered, rather than being
beaten into shape by the gates the way gen-18's were.

> **STATUS: NOT SETTLED.** I did not settle whether the three-stage unwrapping
> rule is computable in polynomial time on arbitrary instances, and I did not
> prove hardness.

What it did ship is three Lean-verified lemmas about finite sets of integer
vectors, which are the lexicographic core of any future algorithm *or* hardness
argument:

1. **Layer scalarisation.** With $Q \le Q_{max}$, $\mathrm{range}(L) \le
   L_{range}$, $K > L_{range}$ and $H > K\,Q_{max} + L_{range}$, every
   minimiser of $H\,C + K\,Q + L$ is $C$-minimal, then $Q$-minimal among those,
   then $L$-minimal.
2. **One-coordinate scalarisation.** With coordinates in $[-D, D]$ and $B =
   2D+1$, any minimiser of $k_j + B\,F$ is $F$-minimal and, among those, has
   the least $j$-th coordinate.
3. **Greedy lexicographic minimality**, as above.

All three re-derived here before recording, analytically and by 80,000
randomised trials, zero counterexamples. Lemma 1 follows from integrality: if
$C(y) < C(x)$ then $\Delta \le -H + K\,Q_{max} + L_{range} < 0$. Lemma 2
because $B$ exceeds the maximum coordinate swing $2D$. Lemma 3 because at the
first differing coordinate prefix-minimality forces $a_i < y_i$.

### Why the refusal is the interesting part

The answer names three gaps between the toolkit and an algorithm, and the
second is the one that matters:

> (a) prove that the feasible integer flows … with $|k_e| \le D$ for an
> explicitly computed $D$ contain all stage-2-optimal flows, (b) prove that
> minimising the scalarised objective over that box plus prefix constraints is
> a separable convex integer flow problem solvable in polynomial time … the
> coordinate bound is a nontrivial flow-specific fact that I did not verify.

That is exactly the obstruction, and it was predicted before the run finished.
The natural encoding of stage 3 as an integer objective, $L(k) = \sum_e k_e
B^e$, has values exponential in $|E|$ but bit-length only $m \log(2D+1)$ — so
the coefficients are *not* the problem, and a branch chasing them (`B4.3` did)
is chasing the wrong thing. The problem is $D$. The standard reduction from
separable-convex to linear min-cost flow splits each edge into one arc per unit
of capacity, giving $O(mD)$ arcs, which is polynomial only if $D$ is polynomial
in the **encoding size** rather than in the input values. gen-18's bound $D =
A\,C(k_0) + Q(k_0)$ with $A = Q(k_0)+1$ is pseudo-polynomial: large weights
make it exponential in the encoding.

A branch could have passed its own scalarisation lemma and inherited
"polynomial-time flow solving is standard background" from gen-18's answer,
where the costs were small. That is precisely how gen-18 shipped a restriction
it never priced. This run declined to.

### What the harness did badly

**Three branches proved the same lemma.** `B4` at turn 8 as $H_f/H_g$, `B3` at
turn 19 as $H/K$, `B4.2` at turn 20 as $\alpha/\beta$ — the same scalarisation
result with renamed variables, three separate slow-tier Lean proofs. They were
not failing to discover each other's work: each claim was served through the
shared-artifact block five times. The block renders claim text only, no code,
so a branch cannot cite a theorem statement it has never seen and re-deriving
is the only move available to it. The branches behaved rationally; the channel
is underpowered.

**The sharing channel spent three quarters of itself on the prompt.** Of 91
shared-artifact hits, 67 served *seeded* gen-19 artifacts — the dipole-square
material gen-20's own statement already prints in full under a heading saying
not to reprove it — against 24 for artifacts the run itself produced.

**The hardness direction collapsed.** `B4.2` forked at turn 12 with the goal
"prove no polynomial-time exact algorithm exists … or identify a concrete
NP-hard subproblem", and within eight rounds both of its own children were
pursuing algorithms. Nothing in the beam was attempting hardness when the run
ended. Forks inherit the parent's recent context, and the parent had just been
proving scalarisation lemmas.

**The best branch died on tool mechanics.** `B1`, which had rediscovered the
greedy characterisation, was culled at turn 32 after calling `proof_step`,
being told to call `proof_start`, calling `proof_start` without its required
arguments, being told, and repeating — five failures, three with the
byte-identical error. The cull was correct on the evidence (13 real failures to
1 success). The harness answering the fifth identical mistake exactly as it
answered the first was not. Run-wide, 29 of 57 failures are four identical
(tool, message) pairs.

### The gates, measured again

Predictions settled 9 met to 27 unmet — better than gen-19's 4 to 22, still
poor. One line changed completely:

| gate | gen-19 | gen-20 |
| --- | --- | --- |
| `tier-escalation` | 0 met / 4 unmet | **2 met / 0 unmet** |
| `milestone` | 0 / 4 | 2 / 3 |

And the artifacts followed: **11 of 11 confirmed artifacts on the slow tier,
against 0 of 16 in gen-19**, with the unfaithful rate down from 44% to 15%.
The difference between the runs is that gen-20's problem statement named
`verify_template`, `proof_start` and `proof_step` as a requirement, where the
gate had only ever described "a slow-tier check" — which is not a callable
name. Suggestive, not measured: one run, different problem, obvious confound.

### Cost, for the first time

Token usage had been parsed by the provider adapter and discarded by the loop
for the project's whole life. It is recorded from this run's turn 164 onward:
441,516 prompt and 185,752 completion tokens over 33 turns, at an 80.5% cache
hit rate.

Measured mid-run at nine active branches the hit rate was **42.6%** — every
branch shares one ~4,900-token system prefix and every branch's own transcript
misses on every turn, growing monotonically. By the end the beam had collapsed
to fewer branches and the rate rose. Running wide costs more than the branch
count alone suggests.

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
Gaussian field, or the torus. What is still open after four runs: Q-A/Q-1 in
general — the tied case, which is the whole difficulty and which both gen-19
and gen-20 left open (vf-izq) — Q-B as a theorem for arbitrary graphs, Q-3
beyond 3×3 and as a function of $n$, and Q-D with nonzero residues or noise.

What gen-20 changes about Q-1 is that it is now open *with a foundation*: three
machine-checked lemmas that any algorithm or hardness proof would need, and a
named obstruction — whether the coordinate bound $D$ is polynomial in encoding
size — rather than a vague sense that the tied case is hard.

**One of the four runs was steered to its answer, and one was not.** gen-19's
Q-2 prompt named the proof strategy — exhibit an instance whose symmetry group
acts transitively on its stage-2 optimal set — and that is what shipped. gen-20
withheld both the strategy and the obvious attack, and `B1` rediscovered the
greedy characterisation anyway at turn 18. So the honest summary is that the
harness can find an attack of that kind, and that gen-19 is not evidence either
way because it was told.

**The gates did work, unevenly.** gen-18's `B2` had `done` refused five times
and `B2.3.3` three, in every case for asserting the thesis when the evidence
supported something weaker; gen-19's audit refused 14 of 32 artifacts outright.
The accepted answers are the ones that state their own restrictions, and that
friction is most of why the shipped text above can be read at face value.
Against that: gen-19's gate *predictions* settled 4 met to 22 unmet, and its
`tier-escalation` gate never once got a branch onto the slow tier. Refusal
works; nudging does not.

gen-20 sharpens that into something actionable. Its predictions settled 9 to
27 — still poor — but `tier-escalation` went 2 for 2 and every confirmed
artifact was slow-tier, the difference being that the *problem statement*
named the tools where the gate had only described a tier. The pattern across
four runs is that a gate changes behaviour when it withholds something or
names something callable, and not when it merely suggests. The counter-example
worth remembering is that gen-20's best branch was still lost to the opposite
failure: the harness repeating an identical unhelpful error five times while a
branch looped on it.

**gen-18 did not finish.** The server died around 2026-08-10 20:15, roughly 20
hours in, with `B2.2`, `B4.3.2` and `B4.3.3` still active. Its row read
`running` for days afterwards (vf-g2l), which is what prompted
`reconcile-orphans!` — crashed runs are now marked `interrupted` at startup
rather than asserting forever. 603 of 300 max turns is turns across all
branches, not scheduler rounds.

**gen-20 crashed too, and that went differently.** The server died at turn 164
when its host ran out of disk. `reconcile-orphans!` marked the row
`interrupted` at the next startup instead of leaving it claiming to run, and
the run was resumed from its journal and went on to finish 33 turns later. The
machinery gen-18's failure prompted is what made gen-20's a non-event. Two
things are still not replayed across a resume and both showed: branch Lean
sessions are process memory and do not survive, and pre-crash turns keep the
categories they were recorded with — so `__no_call__` turns written before the
mechanics fix replayed as failures, which is visible in the tallies above as 21
`mechanics` turns beside 9 older no-call turns still counted as failures.

**The run counts here are not comparable across generations.** gen-20 produced
13 artifacts to gen-17's 97, which reflects what the questions asked for rather
than productivity: gen-17 was mapping a space and gen-20 was trying to close a
single hard question, where most turns are a Lean proof failing to elaborate.
The comparable numbers are the rates — unfaithful share, slow-tier share,
prediction settlement.
