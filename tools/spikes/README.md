# Spikes

Throwaway probes kept because they are evidence, not because they are code.
Each one answers a question a beads issue asks, and the answer is recorded on
that issue.

## `ball.c` — vf-b7e, certified numerics

Arb ball arithmetic, via FLINT 3.6 (Arb has lived inside FLINT since 3.0).

```sh
cc ball.c -o ball -I/opt/homebrew/include -L/opt/homebrew/lib -lflint -lgmp -lmpfr
./ball
```

At 128-bit precision it proves `3.14159 < π` and `π < 3.1416`, and **refuses**
`π < 3.14159`. The refusal is the point: an enclosure does not round its way
into a false bound, so a strict inequality that comes back proved is a proof,
and can carry `confirmed` rather than `empirical`. Octave cannot make that
distinction at any precision.

## `acyclic.p` — vf-5wt, a hammer

TPTP, first-order. The containment core of lemma (B): `reach` transitive,
every edge a reach step, nothing reaches itself, therefore an edge `a→b` puts
`b`'s reachable set inside `a`'s, strictly. Five distractor axioms are mixed in
to see whether relevance matters at this scale.

```sh
vampire --time_limit 30 acyclic.p     # Refutation, 0.002s
eprover --auto --cpu-limit=30 acyclic.p   # SZS status Theorem
```

Both close it instantly. That is the step gen-26 spent effort on by hand in
`a#788` — but only that step: the rank function it feeds, `|V|` minus the
cardinality of the reachable set, is not first-order, so a hammer would have
helped here without proving the lemma.
