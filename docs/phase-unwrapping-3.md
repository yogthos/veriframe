# Q-1: ten runs, and lemma (A) closed

A follow-on to [phase-unwrapping-2.md](phase-unwrapping-2.md). Those four runs
established the three-stage rule and proved it well defined. These nine —
gen-22 through gen-30 — went after the open question it left: **is the rule
computable in polynomial time?**

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

## gen-25 to gen-27: the correctness chain narrows to two steps

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

## gen-28 to gen-30: one step left, and a sixth void artifact

| | gen-28 | gen-29 | gen-30 |
| --- | --- | --- | --- |
| run | `b661192d` | `dc869f5d` | `ca7570d5` |
| turns | 229 | 72 (aborted) | 352 (stopped by hand) |
| confirmed | 10, now **9** | 1 | 3 |
| refuted / retracted | 0 / **1** | 0 / 0 | 1 / 2 |
| branches (culled) | 8 (5) | 3 (0) | 8 (7) |

**gen-30 is the one that moved the question.** `a#836` proves TARGET 1 step 3:
for any type, any relation, and any closed `Chain'` walk of length at least 2,
there is a closed `Chain'` walk that is `Nodup` apart from its repeated
endpoint, embedded in the original as `l = p ++ c ++ q`. This is the
first-repeat extraction the campaign had been circling since gen-27, done by
`by_cases` on whether `l.dropLast` is `Nodup`, splitting at the repeat, and
recursing on the shorter walk with `termination_by l.length`. No `DecidableEq`
or `Fintype` on the vertex type; classical is used only for `by_cases` inside
the proof. Read as a body and verified here. `a#831` and `a#833`, prefix and
suffix `Chain'` preservation, are what it stands on.

**So TARGET 1 needs exactly one thing: a simple cycle's arc set is balanced** —
equal oriented in- and out-degree at every vertex. That feeds `a#777`
(`balanced_support_gives_sign_circulation`, gen-24), which yields the
sign-aligned circulation, which contradicts the per-edge cancellation
arithmetic of `a#718`/`a#723` (gen-22). Then lemma (A) is closed and the
correctness chain has no unverified arrow left in it.

### gen-28's headline result was void — caught at once, recorded three generations late

`a#818` was the run's answer — "the verified contribution is a single partial
lemma, the forward map of an arc-subdivision construction... proved universally
in Lean 4/Mathlib". It is retracted. The statement quantifies over `E`, `V`,
`D`, `tail`, `head`, `k` and concludes two things: that
`(Finset.range (k e)).card = k e`, and that a divergence expression written
with `(Finset.range (k e)).card` equals the same expression written with
`k e`. The second follows from the first by rewriting. **No subdivided arc
type, no unit capacities, and no per-block structure appear anywhere in the
statement.** Its whole content is `Finset.card_range` applied under a sum.

Checked rather than asserted: delete `D` and `hk : k e ≤ D` and the theorem
still elaborates — `ok true, errors [], sorries 0`. A theorem about replacing
each edge by `D` parallel arcs that remains true with `D` removed is not about
subdivision.

This is the **sixth** artifact this campaign has banked as confirmed whose
prose outran its evidence, and the first to clear the **slow** tier — the
defence this document elsewhere calls the answer to exactly this failure. The
two Octave cross-checks (`a#822`, `a#823`, 200 random graphs each, two
constructions, zero discrepancy) are why it read as solid: they tested the
*real* construction and they pass. They establish that the mathematics is
true. They do not make this artifact prove it, and having them attached made
the artifact look better-evidenced than a bare Lean result rather than worse.

**The detection worked; the bookkeeping did not.** gen-30's own formulation
already carried this diagnosis, in sharper terms than the re-derivation above,
and told that run it had not inherited either `a#818` or the assumed-extraction
lemma. What never happened is the follow-through: the artifact row stayed
`claim_status = 'confirmed'` for three generations, and this document said
nothing. So the result looked live to anything that read the table rather than
that one run's prompt — and since seeding copies exactly the rows marked
confirmed, a generation launched without that hand-written quarantine list
would have inherited it. Retracting the row is what makes the finding survive
the person who made it. The lesson is not that the review missed something; it
is that a finding recorded only in the next run's prompt expires with that run.

The forward map is therefore still open, and `vf-0my` still needs it.

### The drift is now measurable

gen-28 also banked `a#814`, `a#817` and `a#820` — nondecreasing marginal costs
for the stage-2 quadratic, for the integer weighted-L1, and for the real
weighted-L1. "Nondecreasing marginal costs" is listed under **Proved** above,
from gen-22 to gen-24. A third of the run's confirmed output re-establishes a
settled result in three spellings.

That is the same drift named after gen-24, gen-26 and gen-27, and it is the
fourth run to show it. gen-29 is the degenerate case: 72 turns, aborted, one
list lemma stating that `Chain'` survives dropping the head.

Every mechanism built since — the sketch tool, the explore/build phase gate,
beam diversity on sketches, retrieval before drafting, and the forced reframe
that withholds an approach instead of culling for it — is aimed at this. None
of them has yet run on a Q-1 generation: `sketch` artifacts number **zero**
across gen-28, gen-29 and gen-30, because the tool landed after gen-30
finished. gen-31 is the first generation that will have them, and the drift
rate is the thing to watch.

## gen-31: gaps 1 and 2 of the last step, closed

Run `f4b53e8f`, deepseek-v4-pro, still in flight at 308 turns. Formulated
against the single remaining step of TARGET 1, with the difficulty named
explicitly: `a#836` returns a LIST OF VERTICES and
`balanced_support_gives_sign_circulation` consumes a `Finset E` with balance
stated as sign-oriented indicator sums, so the work is (1) choose an edge per
consecutive pair, (2) show the chosen edges are distinct, (3) turn that into
two equal `Finset.sum`s.

**(1) and (2) are proved**, bodies read rather than labels trusted:

- `chain_exists_nodup_edge_list` (`a#862`, slow tier) and its relation-general
  form `sign_support_walk_edge_list` (`a#864`, slow tier): from a nonempty
  `Chain'` walk whose `dropLast` is `Nodup`, a `Nodup` edge list `es` with
  `es.length + 1 = l.length`, `es.map src = l.dropLast` and
  `es.map dst = l.tail`. Structural induction, `cases hc with | cons_cons` to
  take the step witness and the tail chain, distinctness by a membership
  argument through the map. Every hypothesis is consumed.
- `chain_exists_nodup_edge_list_nonzero` (`a#865`): the same carrying
  `∀ e ∈ es, k e ≠ 0`, which is exactly the `hsupp` that `a#777` requires.
- `a#866` cross-checks the construction exhaustively in Prolog over all 16
  two-vertex two-edge orientation assignments.

**(3) is proved too** (`a#870`, `cyclic_dropLast_tail_count_eq`): for a
nonempty list with `l.head? = l.getLast?`, `l.dropLast.count v = l.tail.count v`
for every `v`. Cases on the list, `List.dropLast_append_getLast?` to split the
tail as `dropLast ++ [a]`, then `count v (x :: m) = count v (m ++ [x])`
(`a#869`) and a rewrite. Both hypotheses are used.

It took two attempts, and the first is worth recording. `a#868` proved the
right thing and DESCRIBED it wrongly — the claim named `dropLast s ++ [a]`
where the theorem said `s ++ [a]`, which is a different list, and spoke of a
closed `l` the statement never mentioned. The faithfulness judge refused it
with a counterexample (`s = [b, c]` gives `[b, c, a]` against `[b, a]`), and
the branch restated it correctly on the next turn. That is the slow tier
catching precisely the class of error that let `a#818` through in gen-28 —
here in the harder direction, where the mathematics is entirely sound and only
the prose is wrong.

**TARGET 1's last step is PROVED.** `a#876`,
`simple_cycle_arc_set_balanced`: for a sign-oriented simple closed walk `c`
(`2 ≤ c.length`, `c.head? = c.getLast?`, `c.dropLast.Nodup`, chained by
`∃ e, signTail e = x ∧ signHead e = y ∧ k e ≠ 0`), there is a `Finset E` that
is nonempty, supported only where `k ≠ 0`, and balanced in the indicator-sum
shape

    ∑ e, if e ∈ C then if 0 < k e then if tail e = v then 1 else 0
                                  else if head e = v then 1 else 0 else 0
  = ∑ e, if e ∈ C then if 0 < k e then if head e = v then 1 else 0
                                  else if tail e = v then 1 else 0 else 0

which is character-for-character the `hCnonempty`/`hsupp`/`hbal` triple that
`balanced_support_gives_sign_circulation` (`a#777`, gen-24) consumes. The
artifact carries its own supporting lemmas and elaborates as a unit;
re-elaborated here in a fresh Lean session outside the run — ok, no errors, no
sorries, 7,907 characters.

The proof is the composition below, and every hypothesis is used: `hc_len` and
`hc_closed` give nonemptiness and the cyclic count identity, `hc_nodup` and
`hc_chain` build the edge list.

**The pieces it composes:**
`a#865` gives a `Nodup` edge list `es` with `es.map L = l.dropLast`,
`es.map R = l.tail` and `k e ≠ 0` throughout; `a#870` gives
`l.dropLast.count v = l.tail.count v`. Together, every vertex is the source of
as many edges of `es` as it is the destination — balance, in list-count form.
`a#873`/`a#875` move an indicator sum over `univ` to a `Finset` sum, and
`a#874` turns that into a list count — the step `es.Nodup` licenses, since
without it `toFinset` collapses duplicates and `Finset.sum_insert` does not
apply. `a#871`'s `signTail`/`signHead` are what convert the nested
`if 0 < k e` conditionals into a single indicator, which is the only place
those definitions do work.

**What remains for lemma (A):** feed `a#876`'s output to `a#777` for the
nonzero sign-aligned circulation, and contradict the per-edge cancellation
arithmetic of `a#718`/`a#723` (gen-22). Both are inherited and proved. That is
the last unverified arrow in the correctness chain.

### `a#880` does NOT close lemma (A), and says so in its own claim

`a#880` (`balanced_sign_support_contradicts_stage2_optimal`) is confirmed and
is **conditional**: it takes the statements of the balanced-support circulation
theorem and the C-restricted contradiction core as HYPOTHESES rather than
containing their proofs, so what it establishes is modus ponens over assumed
components. Its claim is honest about this — "if the verified ... theorem and
the verified ... core are available as hypotheses, then False follows" — but
its closing sentence, "this is the missing composition", is the one a later
generation would quote.

Lemma (A) closes when ONE artifact elaborates as a unit containing the
extraction, the edge-list construction, the count identity, `a#876`, the
circulation theorem, the contradiction core, and a composition that discharges
all of them, concluding with no component assumed. `a#876` already demonstrates
the pattern: it carried five supporting lemmas in one 7,907-character file.

Recorded here rather than only in the run's prompt, because that is how the
`a#818` finding was lost for three generations.

### An assumption the chain has carried since gen-22

`a#718`, the cancellation lemma the whole acyclicity argument terminates in,
is stated with `(w k2 χ : E → ℤ)` — **integer** weights. Q-1 says weights come
from a coherence map, which is real-valued. gen-31's first attempt at the final
composition (`a#877`) inherited the restriction and was refused as unfaithful
for claiming 'nonnegative weights' while proving the integer case; the judge
was right about the mismatch and could not know the restriction was nine
generations old.

It is probably reparable rather than fatal: `C(k) = Σ w(e)·|k(e)|` is
positively homogeneous in `w` and `Q` is weight-free, so scaling rational
weights by a common denominator preserves both optimal sets, and every real
instance has rational weights. But that rescaling step is not proved and the
dependency is not written down anywhere. Until it is, the honest statement when
lemma (A) closes is *the chain is complete for integer-weighted instances* —
not *for Q-1*. Tracked as vf-a1f.

### Two operator interventions, and what they were for

The run's first four artifacts were all polynomial arithmetic on a work bound
its own inheritance already contained — the drift pattern, on turn 15, in a
run whose formulation forbids it in as many words. No gate can see this: every
guard keys on failure or on absence of progress, and a branch banking easy true
off-target results never fails and never stalls (`vf-1ep`).

A directive at turn 46 fixed something the harness could not have known. In
this Mathlib the list-chain predicate is `List.IsChain`; `Chain'` survives as
an alias, so the campaign's inherited statements still elaborate while every
LEMMA is named `isChain_*`. The corpus is written in `Chain'` and the index
holds 72 `isChain_*` entries, so branches searching in their own vocabulary
found nothing. `B1` spent seven consecutive turns on correct, on-target
queries — `Finset.sum_filter`, `List.toFinset sum Nodup`,
`List.Chain' get consecutive elements relation` — and the last returned
`Acc.list_chain'` and five `Ico` interval lemmas. The lemma it wanted exists as
`isChain_iff_get`. Tracked as `vf-i5q`.

Before turn 46: four artifacts, all drift. After: nine, all on target. That is
n=1 and confounded — three harness fixes landed in the same window — so it is
a sequence, not a measurement.

## gen-32: lemma (A), proved

Run `d7a1a740`, first artifact, turn 54. `a#887`:

```lean
theorem sign_oriented_support_acyclic
    {V E : Type*} [Fintype V] [Fintype E] [DecidableEq V] [DecidableEq E]
    (tail head : E → V) (w k : E → ℤ)
    (hw : ∀ e : E, 0 ≤ w e)
    (hCmin : ∀ y, sameDivergence y k → Ccost w k ≤ Ccost w y)
    (hQmin : ∀ y, sameDivergence y k → Ccost w y = Ccost w k → Qcost k ≤ Qcost y) :
    ∀ x : V, ¬ Relation.TransGen
      (fun x y => ∃ e, signTail tail head k e = x ∧ signHead tail head k e = y ∧ k e ≠ 0)
      x x
```

No component is a hypothesis — only nonnegative weights and the two
optimality conditions, which are what the problem gives. Verified three ways:
re-elaborated from the database in a fresh Lean session outside the run
(24,091 characters, no errors, no sorries); the source contains no `axiom` and
no `sorry`; and

    #print axioms sign_oriented_support_acyclic
    'sign_oriented_support_acyclic' depends on axioms:
      [propext, Classical.choice, Quot.sound]

which is Lean's standard three and nothing else. That last check is the one
that would have caught every void artifact this campaign has banked, and it is
worth making routine.

**THE CORRECTNESS CHAIN IS COMPLETE.** Every arrow from "stage-2 optimal"
down to "each prefix subproblem is separable convex" is now engine-verified.

**Q-1 IS NOT SETTLED.** What closes is correctness, not complexity. The
remaining arrow is (C), polynomial time, and that is where the
instance-class/arbitrary-instance distinction lives: arc subdivision is
polynomial in the numeric VALUE of S, hence pseudo-polynomial when b is
written in binary, so the arbitrary-instance case still needs a convex
min-cost-flow subroutine polynomial in log S, or a hardness result. Neither
exists in any run so far.

The weight hypothesis is `w : E → ℤ`. That restriction is removable and is not
mathematics — see `vf-a1f`, where the same cancellation core is verified over
an arbitrary ordered ring.

### What it took, and what that says about the harness

gen-31 proved every component and could not assemble them, spending its last
180 turns producing conditional compositions that assumed what they were meant
to prove. The obstacle was `vf-vw4`: artifacts cannot cite each other, so
composing meant fetching five of them and retyping 19,000 characters. gen-32
was given those sources verbatim in its problem statement and closed the lemma
in 54 turns.

So the binding constraint on the final step was not mathematical. It was that
the harness had no way to let a proof stand on a proof.

One incidental confirmation: the elaboration emits nineteen
`List.Chain' has been deprecated: Use List.IsChain instead` warnings. The
campaign's entire corpus is written against a deprecated alias whose lemmas
are indexed under the new name, which is exactly the retrieval failure
`vf-i5q` describes.

## The ledger does not hold what it says it holds

Every citable Lean artifact in gen-33's inheritance was elaborated, one at a
time, in a fresh session (`docs/corpus-audit-2026-08-19.edn`):

| | |
| --- | --- |
| citable Lean artifacts | 83 |
| compile | 51 |
| **do not compile** | **32 (39%)** |

By cause: 8 unsolved goals, 8 tactics that no longer close (`linarith`,
`omega`, `introN`), 6 that do not parse, 5 other, 3 that call an identifier
they never define, 2 whose lemma signatures moved.

**a#718 and a#774 are both in the failing set**, and both are load-bearing:
a#718 is the cancellation core the acyclicity argument terminates in, a#774 the
box bound. So while every arrow of the correctness chain was engine-verified
*when it was checked*, **the chain cannot today be reassembled from its
recorded parts**.

Lemma (A) is unaffected. `a#887` elaborates and `#print axioms` reports only
propext, Classical.choice and Quot.sound. It survives because it carries its
own proof of every step rather than citing the corpus.

### Three causes, all now closed

**The interactive path banked a reconstruction it never elaborated.**
`proof_step` assembled `theorem <stmt> := by <tactics>` and recorded it; each
tactic had been checked against a proof state, the assembled declaration never
was. It now re-elaborates before banking.

**Multi-line tactics had their indentation destroyed.** The assembly used
`(str/join "\n  " tactics)`, which indents only where tactics meet, so a
tactic's continuation lines stayed at the column the branch wrote them in —
column 0 relative to the tactic. That is why a#718 has `have hCeq` at column 0.
Every line of every tactic is now indented as a block.

**`axiom` was accepted by the Lean lint.** An axiom is `sorry` with no warning,
and unlike a hypothesis it does not appear in the statement of the theorem that
uses it. A branch probed exactly this and the probe was recorded as confirmed.
The lint now refuses axioms in both normal and sketch mode.

### Why it took ten generations to notice

Nothing ever re-elaborated a banked artifact. `seed-from-run!` copies claim and
code forward as text, and the only way to build on a result was to reproduce it
by hand — where a branch naturally re-indents as it types and never sees the
corruption. The same thing happened here while checking whether the weight
restriction was removable: a#718 was retyped, properly indented, and elaborated
fine, which says nothing about the artifact as stored.

Citation is what made it visible, by forcing inherited code through the
elaborator unchanged. The mechanism built to save typing turned out to be the
first thing that ever checked the corpus.
