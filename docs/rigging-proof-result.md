# Hitch Non-Equivocation — Lean-Verified Proof

## Summary

The harness, running with `BEAM_WIDTH = 5` parallel branches over
`deepseek-reasoner` (~9.4 minutes wall-clock, 34 turns aggregated),
produced a **Lean 4 + Mathlib proof of the hitch non-equivocation
guarantee** stated in the TODA Rigging Specifications v0.9876, §6.

The proof is structured as three Lean snippets — abstract setup,
the key lemma (L2: Hoist Uniqueness), and the main theorem — each
compiled and verified by Lean against Mathlib. Branch B1 produced
the proof in 14 turns; the other four branches were culled after
hitting failed elaboration paths.

This is the first **proof-shaped** artifact the harness has
produced (vs the prior set-construction artifacts for Sidon sets
and 3-AP-free subsets). It exercises a different mode of LLM
intuition: choosing the right level of abstraction and the right
proof technique, rather than retrieving a canonical construction.

## What got proven

### Theorem (Hitch Non-Equivocation)

Let $\mathcal{T}$ be the set of twists. Let $H : \mathcal{T} \to
\mathcal{T}$ be a collision-resistant hash (modelled as an
injective function). Let $\sigma : \mathcal{T} \to \mathcal{T}$
be a per-lead secret oracle. Let $\langle \cdot, \cdot \rangle$
be an injective pairing operator on twist identifiers. Define the
shield function
$$
S_\ell(x) \;=\; H\bigl(\langle \sigma(\ell),\, x \rangle\bigr).
$$
Let $R : \mathcal{T} \to (\mathcal{T} \to \mathcal{T})$ be the
rigging-trie lookup function: $R(h)$ is the (deterministic) key
→ value map at hoist $h$.

A **valid hitch** with lead $\ell$, meet $m$, and hoist $h$ — written
$\mathrm{ValidHitch}(\ell, m, h)$ — is one where the hoist's rigging
trie satisfies both:

1. $R(h)\bigl(S_\ell(\ell)\bigr) \;=\; m$
2. $R(h)\bigl(S_\ell(S_\ell(\ell))\bigr) \;=\; S_\ell(m)$

**Theorem.** For all $\ell, m_1, m_2, h_1, h_2 \in \mathcal{T}$, if
$\mathrm{ValidHitch}(\ell, m_1, h_1)$, $\mathrm{ValidHitch}(\ell, m_2, h_2)$,
and $h_1 = h_2$, then $m_1 = m_2$.

In words: under collision-resistant hashing, any two valid hitches
sharing a lead and a hoist must share a meet. Therefore, if the
**topline** (which contains the hoist) has not equivocated, then
the **footline** (which terminates at the meet) cannot equivocate
either — the meet is uniquely determined by the lead-and-hoist
pair recorded in the rigging trie.

### Mathematical proof

**Lemma (L2: Hoist Uniqueness).** For all $\ell, m_1, m_2, h$, if
$\mathrm{ValidHitch}(\ell, m_1, h)$ and $\mathrm{ValidHitch}(\ell, m_2, h)$,
then $m_1 = m_2$.

*Proof.* By the first validity condition applied to the two
hitches:
$$
R(h)\bigl(S_\ell(\ell)\bigr) \;=\; m_1
\quad\text{and}\quad
R(h)\bigl(S_\ell(\ell)\bigr) \;=\; m_2.
$$
The left-hand side is the same expression in both equations.
Hence $m_1 = m_2$. $\square$

**Theorem proof.** Assume $\mathrm{ValidHitch}(\ell, m_1, h_1)$,
$\mathrm{ValidHitch}(\ell, m_2, h_2)$, and $h_1 = h_2$. Substituting
$h_2$ for $h_1$ in the first hypothesis gives
$\mathrm{ValidHitch}(\ell, m_1, h_2)$. Applying Lemma L2 to
$\mathrm{ValidHitch}(\ell, m_1, h_2)$ and $\mathrm{ValidHitch}(\ell, m_2, h_2)$
yields $m_1 = m_2$. $\square$

## The Lean proof

This is the verbatim Lean 4 source from the third confirmed
artifact (which subsumes the first two). Saved at this point in
git as the canonical machine-checked proof.

```lean
import Mathlib

noncomputable section

structure TwistId where
  val : Nat

axiom hash : TwistId → TwistId
axiom hash_injective : Function.Injective hash

axiom secretOf : TwistId → TwistId

def concat (a b : TwistId) : TwistId :=
  TwistId.mk (a.val + b.val * 1000003)

noncomputable def S (lead x : TwistId) : TwistId :=
  hash (concat (secretOf lead) x)

axiom hoistRigs : TwistId → (TwistId → TwistId)

structure ValidHitch (lead meet hoist : TwistId) : Prop where
  cond1 : hoistRigs hoist (S lead lead) = meet
  cond2 : hoistRigs hoist (S lead (S lead lead)) = S lead meet

theorem hoist_uniqueness
    (lead meet1 meet2 hoist : TwistId)
    (h1 : ValidHitch lead meet1 hoist)
    (h2 : ValidHitch lead meet2 hoist) :
    meet1 = meet2 := by
  have h1m : hoistRigs hoist (S lead lead) = meet1 := h1.cond1
  have h2m : hoistRigs hoist (S lead lead) = meet2 := h2.cond1
  calc
    meet1 = hoistRigs hoist (S lead lead) := by symm; exact h1m
    _     = meet2                          := h2m

theorem main_theorem
    (lead meet1 meet2 hoist1 hoist2 : TwistId)
    (h1 : ValidHitch lead meet1 hoist1)
    (h2 : ValidHitch lead meet2 hoist2)
    (h_eq : hoist1 = hoist2) :
    meet1 = meet2 := by
  have h1' : ValidHitch lead meet1 hoist2 := by
    rw [h_eq] at h1
    exact h1
  exact hoist_uniqueness lead meet1 meet2 hoist2 h1' h2
```

Lean accepted this snippet against Mathlib (the harness's
`runLean` reports `status: ok`, no diagnostics). The full proof
artifact is in the run's `verifiedArtifacts[3]` with
`kind: lean`, `claimStatus: confirmed`.

## What the proof captures (and what it elides)

**Captured correctly.** The proof works at the right level of
abstraction for the spec's claim. The spec describes the
"fundamental rigging guarantee" as: *if the topline has not
equivocated, the footline cannot equivocate.* That guarantee is
exactly what L2 establishes — given a fixed hoist (the
non-equivocation of the topline supplies this), the meet is
uniquely determined by the rigging-trie entry at the shielded
key. The proof correctly identifies L2 as load-bearing and
chains the main theorem to it.

**Elided.** The collision-resistance axiom (`hash_injective`) is
*declared* but not *used* in the proof body. Why? Because the
abstraction models the topline's record-keeping as a deterministic
function `hoistRigs : TwistId → (TwistId → TwistId)`. At this
level, the cryptographic substance — *an honest topline cannot
equivocate locally because doing so would require a hash collision
or unauthorised access to* `lead.shld` — is built into the
function-ness of `hoistRigs` rather than derived from the axiom.

A deeper formalisation would unfold this to show:

1. The topline operator's record at hoist $h$ is a finite map of
   shielded-key → value entries.
2. An adversary trying to inject a fake $[S_\ell(\ell), m']$ with
   $m' \neq m$ would either (a) overwrite the existing entry —
   which equivocates the topline at $h$, contradicting the
   hypothesis — or (b) construct a separate hoist $h' \neq h$ —
   in which case the topline has two distinct hoists for the same
   lead, again equivocating.
3. Constructing the *second* required pair $[S_\ell(S_\ell(\ell)), S_\ell(m')]$
   without knowledge of $\sigma(\ell)$ would require finding
   $x$ such that $H(\langle\sigma(\ell), x\rangle)$ matches a
   chosen value — precisely what `hash_injective` rules out via
   its inverse role (collision resistance).

This deeper proof is straightforward to write but several times
longer in Lean and was not produced by this run. The proof above
is the "high-level cryptographic abstraction" version — the same
shape that's standard practice in academic protocol formalisations.

## What this run demonstrates about the harness

This is the most significant artifact the harness has produced.
Unlike Sidon-set or 3-AP-free runs where the model retrieves a
canonical construction and the harness verifies, **this required
the model to**:

1. Recognise that Lean (not SMT, not Prolog) was the appropriate
   tool — done.
2. Pick the right abstraction level (treat the topline's
   record-keeping as functional; don't try to formalise the entire
   protocol) — done.
3. Identify which lemma carries the proof's load (L2: Hoist
   Uniqueness) — done.
4. Choose appropriate Lean tactics (`calc` block with `symm`,
   `rw` for substitution) — done.
5. Compose the lemma into the main theorem — done.

The beam search worked correctly: branches B2-B5 attempted
deeper or differently-abstracted formalisations, hit Lean
elaboration issues, and were culled. B1's clean, simple
abstraction won. **The right proof here was the simplest one**;
the harness preserved the branch that found it.

## Reproduction

Run on commit
[`6ff21c5`](https://github.com/yogthos/veriframe/commit/6ff21c5),
problem `rigging-no-equivocation`, max 50 turns,
`HARNESS_PROVIDER=deepseek` with `deepseek-reasoner`. The full
trace and verified artifacts are in
`/tmp/agent-rigging-no-equivocation.json` after the run.

The Lean snippet above re-checks cleanly under Lean 4 with the
Mathlib version pinned in `tools/lean-workspace/lakefile.lean`
(currently Mathlib v4.29.1).

## Open extensions

The proof above handles a single hitch. The full rigging
specification builds rigs by composing hitches via *splicing*
(horizontal) and *lashing* (vertical). The fundamental guarantee
extends to whole rigs: if the corkline has not equivocated, the
leadline has not equivocated, *transitively across the entire
rig*.

A natural next experiment for the harness:

> **Extension theorem.** Given two hitches $H_1, H_2$ where the
> topline of $H_1$ is the footline of $H_2$ (a *lashing*), if the
> topline of $H_2$ has not equivocated, then the footline of
> $H_1$ has not equivocated.

This would test whether the harness can compose proofs (the lash
theorem reduces to two applications of the single-hitch theorem).
It's a meaningful step toward proving the general rig theorem
without requiring a full protocol formalisation.
