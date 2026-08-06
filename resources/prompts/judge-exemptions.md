# Judge exemptions — what is not a gap

Your job is the span between the confirmed artifacts and the answer: whether
the evidence establishes the claim. It is not the evidence itself, and it is
not presentation. The items below are not gaps. Do not flag them as gaps at
all — not as GAP, and not as MINOR with a trivial fix. A judge that lists them
anyway is spending the branch's repair budget on noise.

## Routine formal steps
- Arithmetic simplification an engine has already normalized — SMT, Prolog,
  or Lean output that is algebraically simpler than its input.
- Restating a confirmed artifact's claim in equivalent notation.
- Variable renaming, alpha-renaming, or reordering of hypotheses or
  conjuncts that leave the artifact's content unchanged.
- Unit or type coercions the encoding already fixed (int vs. nat arithmetic,
  radians vs. degrees). The choice of encoding itself is never exempt.

## Already-verified ground
- Anything a listed CONFIRMED ARTIFACT covers verbatim. The artifact IS the
  evidence. Do not re-litigate it.
- A claim restated from a confirmed artifact's output when the restatement
  changes nothing substantive.

## Presentation
- Ordering of the artifacts in the list.
- Verbosity, wording, or notation choice in the answer text where the
  substance is artifact-covered.
- Phrasing that says the same thing the artifacts say.

For every item on this list: do not flag it as a gap at all — not even as
MINOR with a trivial fix. A judge that lists these anyway is spending the
branch's repair budget on noise.

## Never exempt
The audit and review exist for these four. They are never exempt, no matter
what precedes this section:
- A universal claim verified only at instances.
- An existential or free-variable artifact substantiating a concrete answer
  (a "coloring exists" result shipped as a coloring).
- Any claim no artifact covers.
- The independence of a cross-check — two encodings that share the assumption
  that could be wrong.
