# Chromatic numbers of 23 circulant graphs not covered by Barajas-Serra

Computed 2026-08-20. These are the instances a public open-problem
database listed as unsolved that survive a literature filter.

## What was already known

The database listed 280 circulant chromatic numbers as open. Of those:

- 140 have two-element connection sets and fall to the known closed form
  for chi(C_n(1,k)).
- 140 have three-element connection sets. Barajas, J. and Serra, O.,
  "On the chromatic number of circulant graphs", Discrete Mathematics 309
  (2009) 5687-5696, computes chi(G(Z_N, D)) for D = {a,b,c} at every
  N >= 4bc. 117 of the 140 satisfy that.

So 257 of 280 are settled by published work. The 23 below are the rest.

## Method

For each instance, an SMT encoding of k-colourability is handed to z3.
Vertex 0 is pinned to colour 0 to break the rotational symmetry. The
smallest k with a model is chi, provided k-1 came back unsat. Every
model is re-checked edge by edge in the caller rather than trusted:
a `sat` verdict on a mis-stated encoding proves nothing about the graph.

The two chi = 4 verdicts rest on an `unsat` at k = 3, which is the only
substantive solver claim here (chi >= 3 elsewhere is just an odd cycle,
since 1 is in every connection set and every N is odd). Both were re-run
under a boolean one-hot encoding as well as the integer encoding, and
both agree. One encoding is one point of failure.

Reproduce with scratchpad/chi.py and scratchpad/batch.py.

## Results

| n | connection set | 4bc | chi |
|---|---|---|---|
| 557 | {1,8,20} | 640 | 3 |
| 563 | {1,9,21} | 756 | 3 |
| 569 | {1,10,22} | 880 | 3 |
| 571 | {1,11,23} | 1012 | 3 |
| 577 | {1,12,24} | 1152 | 4 |
| 607 | {1,6,29} | 696 | 3 |
| 613 | {1,7,30} | 840 | 3 |
| 631 | {1,10,16} | 640 | 3 |
| 641 | {1,11,17} | 748 | 3 |
| 643 | {1,12,18} | 864 | 3 |
| 683 | {1,8,25} | 800 | 3 |
| 691 | {1,9,26} | 936 | 3 |
| 701 | {1,10,27} | 1080 | 3 |
| 709 | {1,11,28} | 1232 | 3 |
| 719 | {1,12,29} | 1392 | 3 |
| 827 | {1,10,22} | 880 | 3 |
| 829 | {1,11,23} | 1012 | 3 |
| 839 | {1,12,24} | 1152 | 4 |
| 983 | {1,10,27} | 1080 | 3 |
| 991 | {1,11,28} | 1232 | 3 |
| 997 | {1,12,29} | 1392 | 3 |
| 1063 | {1,12,23} | 1104 | 3 |
| 1229 | {1,12,28} | 1344 | 3 |

All 23 settled: 21 with chi = 3, 2 with chi = 4.

Both chi = 4 cases have connection set {1,12,24}, where {0,12,24} is a
triangle and 24 = 2*12. The other 21 have no triangle.

## Scope

These are values nobody had recorded, obtained by running a solver on
graphs nobody had run a solver on. That is the whole claim. It is not a
theorem, it needed no new idea, and veriframe was not involved -- this is
z3 driven from a script. Recorded here because it is checkable and
because the literature filter above is the reusable part.
