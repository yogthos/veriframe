# What three runs taught the harness

Findings from watching gen-22, gen-23 and gen-24 work on Q-1. The mathematics
is in [phase-unwrapping-3.md](phase-unwrapping-3.md); this is about the harness.

Every fix below was deployed over nREPL into a running campaign. No run was
restarted, and no generation was lost to a code change.

## The pattern that keeps recurring

**The harness charges interface faults to the counter that decides whether a
branch lives.** Five instances now, four of them found in these three runs:

| # | fault | charged as | should be |
| --- | --- | --- | --- |
| 1 | malformed fence (vf-jki) | failure | mechanics |
| 2 | undeclared `expectedVerdict` | failure | mechanics |
| 3 | `proof_start` argument shape | failure | mechanics |
| 4 | engine outage | failure | neutral |
| 5 | **any argument-shape fault** (vf-v6x) | failure | mechanics |

The fifth was found by asking why gen-24 culled five of eight branches. Across
gen-22/23/24 there are 213 failure-turns, of which **~21 are pure argument
shape — 16 of them `branch_theses` emitting the byte-identical complaint about
its `theses` array.** A branch that mis-serialised its arguments produced no
claim and tested nothing; there is no evidence there about its mathematics.

`:mechanics` rather than `:neutral`, deliberately: the count is still kept, so
a branch looping on bad calls is still bounded. It just stops reading as
mathematics.

A sixth variant of the same shape, in the gate tally rather than the cull
counter: **`safe-state` was absent from `arbiter/settle`'s dispatch**, whose
fallthrough is `false`. Its prediction could never come true, so it fired,
expired and logged unmet — 0 met to 2 unmet across three runs, which reads off
the gate table as a gate the model ignores when nothing was ever checked. There
is now a coverage test that walks the gate table, so the next gate added
without a settle rule fails in CI rather than silently three generations later.

## Withholding beats suggesting

The arbiter already argued this from gen-19/20 data: the gates that changed
behaviour were the ones that *withheld* something, not the ones that suggested.
Two confirmations at larger scale.

**Prefill after a no-call.** gen-22's B1 spent 24 of its 44 turns emitting no
tool call at all — more than half the branch at full token spend. It received
the "[harness] No tool-call block in your response" message every one of those
times and answered in prose anyway, once at 109,360 characters against a
32,768-token cap. 44 of the run's 59 mechanics turns were natural stops, not
truncations: the model finished a page of sound reasoning, said what it would
do next, and never did it.

The mechanism to fix it already existed — ending the request mid-fence so prose
is not an available reply — and was reachable only from a gate decision. Now a
turn that produces no usable call prefills the bare fence.

| | gen-22 | gen-23 | gen-24 |
| --- | --- | --- | --- |
| mechanics turns | 19% | 16% | 13% |
| a no-call followed by another | **38%** | 20% | **5%** |

The second row is the one the fix targets. The first is confounded by
everything else that changed.

*Bare* fence, no tool name: a gate that knows which tool it wants supplies the
name, but here nothing is being steered — the branch had a plan and failed to
act on it, and picking its next call would replace a mechanics failure with the
harness doing the reasoning.

**What the doubled-budget retry does.** Worth recording because it stopped a
wrong change: the retry recovers 35 of 50 truncated turns. "More tokens, not
more steering" really is right for a reply that hit the cap mid-thought. Left
alone.

## Deduplication has to know what it is deduplicating

The claim registry implements the UCLA claim protocol correctly and was wired
to **2 of 6 verdict paths** — `review` and `verify_template`, the two least
used. gen-22 ran 97 slow verifications and the dedup fired **zero** times while
`B3:t11` and `B2.2:t17` both sent Z3 the same claim, byte for byte.

Wiring the engines in exposed the harder half. The registry keys on spelling,
so a lemma restated with renamed variables is a new claim:

| pair | 4-gram Jaccard | what it is |
| --- | --- | --- |
| a#689 / a#693 | 1.00 | byte-identical; the exact key catches it |
| a#687 / a#689 | 0.28 | same lemma, `B` renamed to `D = E·S`, **same branch** |
| a#712 / a#717 | 0.19 | a#717's text reads "(re-verified on this branch)" |

Lexical matching found 1 of at least 3, and no threshold fixes it: these pairs
differ in wording exactly where they agree in content, while a threshold loose
enough to catch them merges facts a run needs apart — injectivity and
surjectivity of the same map, in this campaign's case.

So the comparison is a judge call, on one FTS candidate, and **it refuses
rather than credits**. A wrong "same" would put a claim the branch never proved
into the list the audit and done gates read. The branch gets the neighbour's
exact words and its `fetch_artifact` handle and decides for itself. `PASS` means
*same*, because the verdict parser is fail-closed and the safe reading of an
unsure judge is "let it verify".

**And the scoping bug that followed, which cost real work.** `review` exists to
re-examine a claim the branch has *already confirmed* — so a check refusing
claims already in the pool refuses `review` by construction, every time. gen-23
made 8 refusals and 5 were `review`. Behind it sat a worse one: a *different*
engine reaching the same claim is not duplication at all, because
`consensus/engine-agreement` counts distinct engine kinds precisely on the
grounds that independent empirical checks compose. One rule covers both —
refuse only a **same-engine** repeat.

Field evidence after the fix: 15 same-claim comparisons, every rejection
reasoned on hypotheses and conclusions rather than wording, including cleanly
separating the acyclicity criterion from the path-decomposition bound.

## Say what an engine error means, not just what it was

`verify_lean` handed back raw engine output plus one generic sentence, and the
same classes recurred for three generations. 34 Lean rejections:

| count | class |
| --- | --- |
| 8 | linarith/omega failed |
| 7 | syntax / unexpected token |
| 7 | unsolved goals |
| 4 | failed to synthesize instance |
| 3 | rewrite pattern not found |

**All 8 arithmetic failures are one mistake.** The runs are deriving polynomial
work bounds — `125·E⁵·V⁴ < (2·(E·S)+1)·(…)` — from `E ≤ 2V` and `S ≤ 2V`, which
needs hypotheses multiplied together, using tactics that are linear by
construction and cannot do it. From the goal state that looks identical to a gap
in the mathematics, and a branch reading a tactic limitation as an obstruction
abandons a lemma that is true and one tactic away.

Nine hints now ship, each naming the fault and the tactic that addresses it and
stopping there. **An unmatched error gets no hint at all** — invented advice is
worse than none, because the branch cannot tell the two apart.

Effect is not yet measurable: 2 successes to 2 failures after the deploy
against 3 to 15 before, on n=4.

## Open

- **vf-lho.** gen-23 confirmed 1 of 25 artifacts on the slow tier, and
  `tier-escalation` fired 6 times all run. Branches bank one-shot checks and
  rarely cross-check. This is where a claim whose prose exceeds its proof gets
  in — see gen-22 `a#718` in the companion document.
- **vf-auw / vf-fq6.** Every steering conclusion here is n=1. The prefill
  numbers span three runs and are the closest thing to a trend in the file;
  everything else is a single sample, and gate rates in particular should not
  be acted on until there is a way to A/B them. A high met-rate may only mean
  the gate predicted what the branch would have done anyway.
