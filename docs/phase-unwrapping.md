# 2D phase unwrapping: what one run established, and what it did not

**The question posed.** A Gaussian field $\theta$ on an $n \times n$ torus with
density $\propto \exp\!\big(-\tfrac{1}{2\sigma^2}\sum_{(u,v)}(\theta(v)-\theta(u))^2\big)$,
observed only as $\psi = W(\theta)$ wrapped into $(-\pi,\pi]$. Recover $\theta$
exactly up to a global $2\pi$. At what $\sigma$ is that possible at all
($\sigma_{\mathrm{ML}}$), at what $\sigma$ does the polynomial-time L1
minimum-cost-flow unwrapper succeed ($\sigma_{\mathrm{MCF}}$), and are they
equal?

Practically motivated: InSAR, MRI field mapping and fringe projection all wrap,
and every deployed pipeline carries a hand-tuned coherence threshold that no
theory explains. Literature check found the two relevant bodies of work — the
applied unwrapping literature (Itoh, Goldstein branch cuts, Costantini
minimum-cost flow, all empirical) and topological-defect physics — with nothing
bridging them.

**None of the three questions was answered.** The run never reached $\sigma$,
the field, or the torus. What it did instead was answer a neighbouring question
thoroughly, and that answer is worth keeping.

## What was established, all independently verified

Every claim below was re-derived by hand before being recorded.

**When the L1 minimum-cost flow IS unique.** For every simple graph on four
vertices with positive integer weights, if the source–sink pair has a *unique*
simple shortest path, the only integer flow with cost at most the shortest-path
cost is the indicator of that path. Quantified over all graphs and weightings,
not a single instance, with the search bound justified (weights $\ge 1$ and
simple paths have $\le 3$ edges, so cost $\le 9$).

**Three independent ways uniqueness fails.** Each is a verified counterexample:

| mechanism | configuration |
| --------- | ------------- |
| tied shortest paths | two unit paths $s\!-\!a\!-\!t$, $s\!-\!b\!-\!t$: two optima at cost 2 |
| zero-weight cycle | a disjoint zero-cost 2-cycle makes the optimal set infinite, *even though* the dipole's shortest path is unique |
| dipole interaction | the 4-cycle with dipoles $(0,1)$ and $(2,3)$: unique disjoint shortest paths, all weights positive, still two optima |
| crossing vs straight | two source–sink pairs that can swap partners at equal total cost |

The last two matter most. They show the single-dipole lemma **does not
compose**: uniqueness for each dipole separately does not give uniqueness for
the configuration. On an interferogram with many residues, which $+$ pairs with
which $-$ is cost-ambiguous, and that ambiguity is intrinsic rather than an
artifact of symmetric toy examples.

**The scalable tool.** For any finite edge set with potentials $d_i$ bounded by
weights $w_i$, $\sum_i d_i k_i \le \sum_i w_i |k_i|$ — the LP duality lower
bound, proved in Lean. Exhibit a potential, get a lower bound on every feasible
flow; if it matches a flow you hold, that flow is optimal. Works on any graph
with any number of dipoles.

## What this suggests about the original question

Stated as inference, not as a result. Uniform weights on a grid give enormous
path degeneracy, so the tied-path mechanism applies almost everywhere.
Coherence-derived weights — the standard fix — introduce zero-cost edges, so
the zero-cycle mechanism applies instead. And the dipole-interaction mechanism
needs neither. Taken together this points at
$\sigma_{\mathrm{MCF}} = 0$ for the L1 formulation as usually deployed: the
minimiser is essentially never unique, so "the min-cost flow recovers
$\theta$" fails not because the cost is wrong but because the argmin is a set.
If that is right, the interesting question becomes which tie-breaking rule or
which objective restores uniqueness — which is a question about an algorithm
running in production interferometry pipelines today.

Nobody has proved this. It is where a follow-up should start.

## Why the run did not get further

Diagnostic, and the more useful half of the exercise. 206 turns produced 14
confirmed artifacts, of which seven were the same two-path lemma re-proved
across three engines.

- **Measurements cannot be banked.** 84 `octave_eval` calls produced zero
  artifacts. The branch whose thesis was "empirically locate
  $\sigma_{\mathrm{MCF}}$" was culled at turn 12 with nothing recorded, having
  done most of that simulation. The critic scores confirmed artifacts;
  `verify_octave` wants one scalar boolean; "recovery breaks near $\sigma=0.7$"
  is not one. So the beam selected for whatever was easiest to state as a
  proposition and walked downhill into flow lemmas on five-vertex graphs.
- **`done` checks support, not relevance.** The shipped answer is a true
  statement about four oriented edges. Every substantive token appears in a
  confirmed artifact — it *is* one — so the coverage gate passed it, and the
  LLM audit passed it with "GAPS: none". Nothing asks whether an answer engages
  the problem.
- **Confirmed results do not cross branches.** `HARNESS_SHARE_ARTIFACTS` is off
  by default, so one branch re-proved, less generally, what a sibling had
  proved twenty turns earlier.
- **Four information-flow bugs**, all found and fixed mid-run over nREPL
  without restarting: rejections reported to the branch as successes; the
  reviewer's objection discarded before reaching the branch; the same objection
  missing from the cross-branch failure log; and the `done` gate citing
  grammar ("does", "from", "having") as unsupported assertions.

The first three are not bugs. They are what the harness rewards, and they are
tracked as vf-y6c, vf-2ff and the sharing flag.

## What the model did well, unprompted

The formulation deliberately withheld any hint of the topological-defect
reading — no mention of the XY model, vortices, or unbinding. Within twenty
turns a branch was reasoning about "isolated $\pm$ residue dipoles" and
"separated pairs", which is the right ontology arrived at independently. It
also refuted its own sibling's conjecture, escalated from hand-built instances
to symbolic weights to a universally quantified statement, and responded to a
precise reviewer objection by fixing the encoding exactly as instructed.

The problem shape was right. The harness was the limiting factor.
