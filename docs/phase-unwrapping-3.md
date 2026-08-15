# Q-1: three more runs, and one lemma from an answer

A follow-on to [phase-unwrapping-2.md](phase-unwrapping-2.md). Those four runs
established the three-stage rule and proved it well defined. These three went
after the open question it left: **is the rule computable in polynomial time?**

**The rule.** Fix an order on the edges. On any instance with a nonempty
feasible set of integer corrections:

    stage 1   among all feasible k, minimise C(k) = Σ w(e)·|k(e)|
    stage 2   among those, minimise Q(k) = Σ k(e)²
    stage 3   among those, take the least under the fixed edge order

Stage 3 is the difficulty. The stage-2 optimal set can have 2^m elements, and a
lexicographic tie-break over it must not be carried out by enumeration.

| | gen-22 | gen-23 | gen-24 |
| --- | --- | --- | --- |
| run | `effd6dac` | `c945f38e` | `17d81b2b` |
| turns | 368 | 274 | 152 |
| artifacts | 38 | 33 | 20 |
| confirmed | 32 | 25 | 11 |
| refused as unfaithful | 6 | 6 | 5 |
| confirmed on the **slow** tier | 17 of 32 | **1 of 25** | 5 of 11 |
| recorded failures | 48 | 56 | 29 |
| mechanics turns | 70 | 45 | 20 |
| branches culled | 0 | 3 | 5 |

All three ended with an honest partial. None overclaimed.

## Where the question now stands

The chain from the rule to an algorithm is this, and every arrow but one is
engine-verified:

    stage-2 optimal
      →(A) no directed cycle in the sign-oriented support
      →(B) a rank function exists
      →     |k_e| ≤ S on every supported edge
      →     the box is [−S, S]
      →     box restriction changes neither C-minimality nor Q-minimality
      →     the greedy prefix selector returns exactly the three-stage flow
      →     each prefix subproblem is separable convex
      →(C) …solved in polynomial time

**Proved.** The selector itself — build `a` coordinate by coordinate, each
`a_i` minimising `k_i + B·(H·C(k) + K·Q(k))` over the prefix-restricted subset,
with `0 < K`, `K·Qmax < H`, `B ≥ 2M+1` — returns the lexicographically least
element of the C-minimal-then-Q-minimal set. That is the whole of stage 3 with
no exponential weights, and it is the campaign's central result. Also proved:
per-edge separable discrete convexity, nondecreasing marginal costs, the
telescoping identity, box faithfulness, and coefficient feasibility.

**(B) is proved twice**, independently, in gen-24 (`a#758`, `a#759`): a finite
directed graph with no directed cycle admits a rank function with
`rank(tail e) < rank(head e)` on every supported edge. The bound that consumes
it is proved in the general form (`a#774`): given any acyclic relation
containing the support, every supported edge satisfies `f e ≤ S`.

**(A) is one step from done.** gen-24 proved
`balanced_support_gives_sign_circulation` (`a#777`): a finite edge set in the
sign-oriented support that is balanced at every vertex — equal oriented in- and
out-degree — yields a nonzero integer circulation sign-aligned with `k`. Its
own statement notes this is "the property any directed cycle's arc set has".
What remains is exactly that: **a directed cycle's arc set is balanced.** The
cancellation arithmetic it feeds was proved back in gen-22 (`a#718`, `a#723`).

**(C) is where the question actually turns.** See below.

## Three findings worth keeping

### The instance class is what makes it polynomial, not the flow structure

gen-23 proved the grid dual graph has degree at most 4, hence `E ≤ 2V`, and
every residue satisfies `|b_v| ≤ 2`, hence `S ≤ 2V`. That converts a quantity
written in binary into one polynomial in the instance, and it is the step that
makes the arc-subdivision route work at all. Both work bounds follow and both
check out by hand: `A = E(2D+1) ≤ 4E²V + E`, and `T ≤ 1000·V⁷` — genuinely
`512·V⁷` asymptotically, so the constant is not tight but the exponent is
honest.

gen-24 then stated the boundary precisely (`a#770`): with the strong box
`D = S`, arc subdivision costs `T ≤ 12·E³·S²`, which is polynomial in the
*numeric value* of `S`. **When `b` is written in binary, `S` can be exponential
in the encoding length, so this is pseudo-polynomial, not polynomial.**

So: a polynomial-time algorithm exists for the instance class that ships in
InSAR and MRI field mapping. Q-1 as posed says *arbitrary* instance, and that
case rests on a separable convex integer min-cost flow subroutine polynomial in
`log S` — proximity or capacity scaling rather than arc subdivision. Not
engine-verified in any run so far, and neither is a hardness result.

### Feasibility alone does not bound edge flows, and the optimality step is load-bearing

gen-24's main result is negative. On the directed 4-cycle `0→1→2→3→0`, the flow
`(k01,k12,k23,k30) = (101,100,100,100)` has divergence `b = (1,−1,0,0)`, so
`S = 1`, and yet `|k01| = 101`. Adding `t` units of the all-ones circulation to
the base flow `(1,0,0,0)` preserves every divergence equation while pushing
`k01` to `t+1`.

Verified independently here: the divergence is exactly `(1,−1,0,0)` and
`S = 1`, so the counterexample is correct. It is **not** a stage-2 optimum —
`Q = 40,201` against `Q = 1` for `(1,0,0,0)` — which is precisely the point.
The `|k_e| ≤ S` bound needs stage-2 optimality; it is false for feasibility
alone. Any proof of polynomiality that reaches for the box without the
optimality-acyclicity argument is wrong, and now demonstrably so.

### An artifact's prose can outrun what the engine verified

gen-22's `a#718` ends "hence the sign-oriented support is acyclic". What the
engine checked is the per-edge arithmetic contradiction; the step from "no
sign-aligned circulation exists" to "the support is acyclic" was not verified.
A branch reading the ledger would take it as settled and build on air.

This was caught by reading the artifact against its own proof, not by any
harness mechanism, and it is the strongest argument in the campaign for the
slow tier: an independent `review` is what catches a claim whose statement
exceeds its evidence. gen-23 confirmed **1 of 25** artifacts on the slow tier
(vf-lho), which is where this class of error gets in.

## Independent checks

Run outside the harness, against the runs' own claims:

- **The selector reproduces the rule.** On 3,215 random instances with zero
  weights present, the greedy prefix construction returns the three-stage flow
  exactly — 0 mismatches.
- **The box bound is tight.** On the same instances no stage-2 optimum violates
  `|k_e| ≤ S`, and the bound is *attained* on 2,035 of them.
- **The abstract theorem holds well outside the run's own test regime.** 60,000
  instances with negative coordinates, negative and structureless `C`, `Q`
  unrelated to sum-of-squares, `M = 0`, `Qmax = 0` — 0 mismatches, including
  19,041 with `H` and `B` exactly at their bounds. Weakening either bound by
  one breaks it (709 and 245 failures), so the hypotheses are not decorative.
- **gen-23's arithmetic.** `E ≤ 2V` holds on every masked grid tested (worst
  observed 1.57); `|b_v| ≤ 2` is correct and conservative, the generic range
  being `{−1,0,1}`; both work bounds hold with room.
- **gen-24's counterexample**, as above.

## gen-25 to gen-27: the correctness chain narrows to one lemma

| | gen-25 | gen-26 | gen-27 |
| --- | --- | --- | --- |
| run | `1b1bddc7` | `3c3ac1f0` | `9468647d` |
| turns | 187 (aborted) | 195 | 680 |
| confirmed | 10 | 8 | 10 |
| refused as unfaithful | 0 | 3 | 5 |
| branches culled | 1 | 4 | 6 |

**gen-25 was aborted, and the reason matters more than the run.** It reported
TARGET 1 closed. It was not: the proof's entire body was the tactic
`classical`, which adds a decidability instance and closes nothing. Two of
gen-24's artifacts — both purporting to prove lemma (B) — were the same. The
harness had been reading a Lean reply carrying no `goals` key as an
affirmative "no goals remain", so `(empty? nil)` was true and the proof was
recorded CLOSED. Three void results, seeded forward as inherited CONFIRMED
lemmas.

That is why every artifact quoted below was read as a proof BODY rather than
trusted as a confirmed label, and why runs since carry a quarantine list.

**gen-26 proved lemma (B) properly** (`a#788`): a finite directed graph with no
directed cycle admits a rank function, constructed as |V| minus the cardinality
of the transitive-closure reachable set. For an edge a → b, `Reach b ⊆ Reach a`
by transitivity, `b ∈ Reach a` but `b ∉ Reach b` by acyclicity, so the
containment is strict and the rank inequality follows. Verified here by hand.
It also produced a verified witness that the stage-2 optimum is genuinely
non-unique — two minimisers at Q = 2 on a 4-cycle — which until then had been
a premise rather than a fact.

**gen-27 built the walk-extraction machinery**, which is the route into TARGET
1 that avoids `Fin` arithmetic:

- `transgen_walk` — from `Relation.TransGen r x y`, a list with `Chain' r`,
  head `x`, last `y`. With x = y that is a closed walk.
- `transgen_walk_len` — the same with `2 ≤ length`, so at least one edge.
- `chain_append_step` — appending a step to an r-chain keeps it an r-chain.
- plus list-splitting lemmas: membership gives `l = s ++ a :: t`, and a
  non-`Nodup` list has a repeated element.

All checked by hand. TARGET 1 now needs two steps: extract a SIMPLE cycle from
a closed walk by stopping at the first repeat, then show a simple cycle's arc
set is balanced.

### The drift, three runs running

Each of gen-24, gen-26 and gen-27 hit the hard lemma, backed off, and spent its
remaining turns confirming things that were already settled — small-instance
SMT probes in the first two, cross-engine re-verification of inherited bounds
in the third — then shipped a partial built from those. gen-27's shipping
branch was refused `done` twice before the coverage gate let a third attempt
through, and its answer describes the run's real contribution as "three
confirmed Lean list/Chain′ lemmas", because the branch that writes the answer
can only see its own work.

Every one of those answers was honest about what it had not established. None
of them were wrong. They were just not what the run had been asked for, and the
formulation now says so explicitly: re-verifying inherited work is not
progress, and a branch beaten by TARGET 1 should say which step beat it.

## What to point the next run at

1. **Two steps close TARGET 1**: extract a simple cycle from a closed walk
   (stop at the first repeat — the list-splitting lemmas for this are proved),
   then show a simple cycle's arc set is balanced. Lemma (B) and the walk
   extraction are done and citable by name.
2. **The arc-subdivision reduction theorem itself.** Every runtime bound in the
   campaign is stated as conditional on it. Its ingredients are proved; the
   theorem is not.
3. **The `log S` subroutine, or hardness.** This is the whole of the arbitrary
   -instance question, and gen-24 established the negative boundary that makes
   it unavoidable.
