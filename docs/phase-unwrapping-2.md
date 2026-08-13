# The canonical unwrapping rule: two runs, and what they cost

A follow-on to [phase-unwrapping.md](phase-unwrapping.md). That run answered a
neighbouring question about when the L1 minimum-cost flow is unique. These two
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

| | gen-17 | gen-18 |
| --- | --- | --- |
| run | `0d0c3560` | `65d50333` |
| turns | 523 | 603 |
| artifacts | 97 | 122 |
| confirmed / empirical / refuted | 56 / 9 / 0 | 79 / 5 / 2 |
| branches shipped / culled | 10 / 5 | 7 / 5 |
| recorded failures | 125 | 164 |
| ended | completed | server died ~20 h in, 3 branches still active |

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

**Nothing here answers the original question.** Neither run touched $\sigma$,
the Gaussian field, or the torus. The open items are Q-A in general — which, per
vf-izq, means the tied case specifically — Q-B as a theorem for arbitrary
graphs, Q-C beyond a single 3×3 grid, and Q-D with nonzero residues or noise.

**The gates did work.** gen-18's `B2` had `done` refused five times and
`B2.3.3` three, in every case for asserting the thesis when the evidence
supported something weaker; the accepted answers are the ones that state their
own restrictions. That friction is most of why the shipped text above can be
read at face value. It is also why both runs shipped so many answers that begin
by saying what they do not settle.

**gen-18 did not finish.** The server died around 2026-08-10 20:15, roughly 20
hours in, with `B2.2`, `B4.3.2` and `B4.3.3` still active; its row still reads
`running` (vf-g2l). 603 of 300 max turns is turns across all branches, not
scheduler rounds.
