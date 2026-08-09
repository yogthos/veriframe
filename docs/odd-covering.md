# The Erdős–Selfridge odd covering problem: verified findings

**Problem.** Does there exist a *covering system* of the integers — a finite
set of congruences $a_i \pmod{m_i}$ such that every integer satisfies at
least one — whose moduli are all **odd, distinct, and greater than 1**?

Open since the 1950s. Selfridge offered \$2000 for a construction; Erdős
offered \$25 for a proof that none exists. Every covering system ever found
has a modulus divisible by 2 or 3.

**Known context** (literature, not re-proved here):

- If all moduli are odd, distinct, and *squarefree*, no covering exists
  (Balister–Bollobás–Morris–Sahasrabudhe–Tiba).
- Any odd distinct covering system must have $\operatorname{lcm}$ of moduli
  divisible by 9 or 15 (same authors).
- **Density bound.** A congruence $a \pmod m$ covers integers with density
  $1/m$, so any covering system satisfies $\sum_i 1/m_i \ge 1$. A modulus
  set failing this is ruled out trivially; call a set with
  $\sum_i 1/m_i \ge 1$ **density-feasible**. Only density-feasible sets are
  interesting.

All findings below are engine-verified (Z3, SWI-Prolog, Octave — each claim
confirmed in at least two engines with independently shaped encodings) by
veriframe runs against `deepseek-v4-flash` on 2026-08-06/07. Run ids refer
to the local `veriframe.sqlite3` journal, which stores each claim with the
verification code that confirmed it.

## Finding 1 — $\{3,5,7,9\}$: not density-feasible; exact maximum coverage

*Run `c45ac428`, 5 confirmed artifacts.*

The reciprocal sum is
$\tfrac13+\tfrac15+\tfrac17+\tfrac19 = \tfrac{248}{315} < 1$, so no covering
system with exactly these moduli exists — the density bound alone decides
it, since with $L = \operatorname{lcm}(3,5,7,9) = 315$ the four congruences
cover at most $105+63+45+35 = 248$ of the $315$ residue classes even if
they never overlapped.

The exhaustive refinement: over all $3\cdot5\cdot7\cdot9 = 945$ residue
assignments, the maximum number of classes mod 315 actually covered is
**exactly 195** $= \tfrac{13}{21}\cdot 315$ (attained by
$(a_3,a_5,a_7,a_9)=(0,0,0,1)$), i.e. unavoidable overlap wastes a further
53 classes beyond the density deficit.

## Finding 2 — $\{3,5,7,9,11,13,15\}$: density-feasible, yet cannot cover

*Run `b84d2263`, 7 confirmed artifacts. The main result so far.*

This is the minimal-lcm odd distinct modulus set that is density-feasible:
$$\sum \tfrac1{m}
 = \tfrac13+\tfrac15+\tfrac17+\tfrac19+\tfrac1{11}+\tfrac1{13}+\tfrac1{15}
 = \tfrac{46027}{45045} \approx 1.0218 \ge 1 .$$

**Theorem.** No choice of residues $a_3, a_5, a_7, a_9, a_{11}, a_{13},
a_{15}$ makes $\{x \equiv a_m \pmod m\}$ cover all integers. The maximum
number of residue classes modulo $L = \operatorname{lcm} = 45045$ coverable
is **exactly $32805$**, leaving $12240$ classes uncovered in the best case;
a maximising witness is $(a_3,a_5,a_9,a_{15};a_7,a_{11},a_{13}) =
(0,0,1,2;\,0,0,0)$.

**Proof.** Split the moduli into the *entangled* part $E = \{3,5,9,15\}$
(pairwise sharing prime factors 3, 5) and the *coprime* part
$C = \{7,11,13\}$. Then $L = 45 \cdot 1001$ with
$\gcd(45,1001)=1$, where $45 = \operatorname{lcm}(3,5,9,15)$ and
$1001 = 7 \cdot 11 \cdot 13$. By CRT,
$\mathbb{Z}/L \cong \mathbb{Z}/45 \times \mathbb{Z}/1001$, and the $E$-congruences
constrain only the first coordinate while the $C$-congruences constrain
only the second. Hence $x$ is **uncovered** iff its mod-45 part avoids all
four $E$-congruences *and* its mod-1001 part avoids all three
$C$-congruences, so

$$\#\text{uncovered}(a) \;=\; u_E(a) \cdot u_C(a)$$

where $u_E, u_C$ count uncovered classes mod 45 and mod 1001 respectively.

*Coprime part.* Since $7, 11, 13$ are pairwise coprime, CRT gives
independence of the three avoidance events, so for **every** residue
choice
$$u_C = 1001 \cdot \tfrac{6}{7}\cdot\tfrac{10}{11}\cdot\tfrac{12}{13}
      = 6 \cdot 10 \cdot 12 = 720,$$
i.e. exactly $281$ of the $1001$ classes are covered regardless of the
residues chosen. (Engine-verified by exhausting all $1001$ assignments:
every one covers exactly $281$.)

*Entangled part.* Exhaustive search over all $3\cdot5\cdot9\cdot15 = 2025$
assignments shows no choice covers $29$ or more of the $45$ classes:
$u_E \ge 17$, with $u_E = 17$ attained (e.g. $a_3{=}0, a_5{=}0, a_9{=}1,
a_{15}{=}2$, covering exactly $28$).

*Combining.* $\#\text{uncovered} = u_E \cdot u_C \ge 17 \cdot 720 = 12240 > 0$,
so no covering exists, and the bound is attained by combining the two
witnesses: maximum coverage $= 45045 - 12240 = 32805$. $\blacksquare$

**Remark.** The obstruction is *not* density — density permits this set —
but the rigidity of the entangled moduli: $\{3,5,9,15\}$ can cover at most
$\tfrac{28}{45}$ of the line no matter how its residues are placed, and
the coprime part's coverage is exactly its independence value
$\tfrac{281}{1001}$, with no way to aim it at the entangled part's gaps.
This factorized structure is the shape any impossibility argument for the
general problem would need to defeat: a hypothetical odd covering must use
shared prime factors to *cooperate*, not merely to accumulate density.

## Finding 3 — two more density-feasible sets fall to the same factorization

*Run `61de2075` (generation 3, seeded from run `b84d2263` via `seed_run`),
20 confirmed artifacts.*

The factorization method generalizes exactly as the Finding 2 remark
predicted, with the inherited entangled bound $u_E \ge 17$ for
$E = \{3,5,9,15\}$ re-verified and reused:

**Theorem.** Neither $\{3,5,7,9,11,15,17\}$ nor $\{3,5,7,9,11,13,15,17\}$
— both density-feasible — can form a covering system. Exactly:

- $\{3,5,7,9,11,15,17\}$: $L = 45 \cdot 1309$ with $C = \{7,11,17\}$
  covering exactly $349$ of $1309$ classes for every residue choice
  ($u_C = 6\cdot10\cdot16 = 960$), so maximum coverage
  $= 58905 - 17\cdot960 = \mathbf{42585}$ of $58905$.
- $\{3,5,7,9,11,13,15,17\}$: $L = 45 \cdot 17017$ with
  $C = \{7,11,13,17\}$ covering exactly $5497$ of $17017$
  ($u_C = 6\cdot10\cdot12\cdot16 = 11520$), so maximum coverage
  $= 765765 - 17\cdot11520 = \mathbf{569925}$ of $765765$. $\blacksquare$

**Building block** (branch B2, same run): for the entangled set
$\{3,5,7,9,21\}$ — where $21$ shares factors with both $3$ and $7$, so the
$E \times C$ split does not apply — exhaustive search over all $19845$
residue assignments gives maximum coverage exactly $207$ of $315$
($u \ge 108$). This is the $u_E$ ingredient for any future superset whose
coprime part avoids $\{3,5,7\}$, e.g. $\{3,5,7,9,21,11,13,\dots\}$.

## Finding 4 — richer entangled parts: 25 and 27

*Run `c5dcc35f` (generation 4, seeded from `61de2075`), 8 confirmed
artifacts, all confirmed, none existential.*

Two new exhaustive entangled-part bounds, each settling a density-feasible
superset with the standard factorization ($C = \{7,11,13\}$, $u_C = 720$):

- $u_E(\{3,5,9,15,25\}) = 80$ of $225$ (no assignment covers $\ge 146$), so
  $\{3,5,7,9,11,13,15,25\}$ has minimum uncovered
  $80 \cdot 720 = 57600$ mod $225225$: **cannot cover**.
- $u_E(\{3,5,9,15,27\}) = 47$ of $135$ (no assignment covers $\ge 89$), so
  $\{3,5,7,9,11,13,15,27\}$ has minimum uncovered
  $47 \cdot 720 = 33840$ mod $135135$: **cannot cover**.

Note the trend in entangled efficiency: adding $25$ leaves uncovered
fraction $80/225 \approx 0.356$; adding $27$ leaves $47/135 \approx 0.348$;
the original $\{3,5,9,15\}$ leaves $17/45 \approx 0.378$. Deeper prime
powers chip away at the entangled gap slowly — quantifying that decay
across richer entangled parts is the empirical curve the conjecture's
truth or falsity lives on.

## Finding 5 — richer entangled parts, and the decay curve

*Run `7f4af6b7` (generation 5, seeded from `c5dcc35f`), beam width 3, 15
confirmed artifacts, 2 branches culled by Pareto retention.*

Three new exhaustive entangled bounds, now over sets mixing **three** prime
powers, and two more density-feasible sets settled from them:

$$u_E(\{3,5,9,15,25,27\}) = 221 \ (\bmod\ 675), \quad
  u_E(\{3,5,9,15,21,27\}) = 302 \ (\bmod\ 945),$$
$$u_E(\{3,5,7,9,15,21,27\}) = 255 \ (\bmod\ 945).$$

Combined with coprime parts whose coverage is again exactly their CRT
independence value ($\{11,13,17\}$ leaves $10\cdot12\cdot16 = 1920$ of
$2431$; $\{7,11\}$ leaves $6\cdot10 = 60$ of $77$):

- $S_1 = \{3,5,9,11,13,15,17,21,27\}$, $\sum 1/m = \tfrac{2348807}{2297295} \ge 1$:
  minimum uncovered $302 \cdot 1920 = 579840$ of $L = 2297295$. **Cannot cover.**
- $S_2 = \{3,5,7,9,11,15,25,27\}$, $\sum 1/m = \tfrac{53114}{51975} \ge 1$:
  minimum uncovered $221 \cdot 60 = 13260$ of $L = 51975$. **Cannot cover.**

**The decay curve.** Every entangled bound the campaign has proved, as an
uncovered fraction $u_E / \operatorname{lcm}$:

| Entangled set $E$ | $u_E / L$ | fraction |
|---|---|---|
| $\{3,5,9,15\}$ | $17/45$ | 0.3778 |
| $\{3,5,9,15,25\}$ | $80/225$ | 0.3556 |
| $\{3,5,9,15,27\}$ | $47/135$ | 0.3481 |
| $\{3,5,7,9,21\}$ | $108/315$ | 0.3429 |
| $\{3,5,9,15,25,27\}$ | $221/675$ | 0.3274 |
| $\{3,5,9,15,21,27\}$ | $302/945$ | 0.3196 |
| $\{3,5,7,9,15,21,27\}$ | $255/945$ | 0.2698 |

It decays, and slowly — seven moduli sharing three primes still cannot
cover more than about 73% of the line. Whether the infimum over all odd
entangled sets is positive is, as far as this campaign can tell, the
question the conjecture reduces to: a positive floor $c > 0$ would rule out
every odd distinct covering whose coprime part cannot make up the remaining
$c$, which is infinitely many modulus sets at once. Nothing here yet proves
a floor exists; it is an empirical curve over seven exhaustively-verified
points.

## Finding 6 — the 45 family

*Run `8cb4083d` (generation 6, seeded from `7f4af6b7`), 7 confirmed
artifacts. The run took the campaign's secondary target; the primary
(a family-wide lower bound) did not move — see the next section.*

$u_E(\{3,5,9,15,27,45\}) = 44$ of $135$ (fraction $0.3259$), confirmed by
independent Prolog enumeration, settling two more density-feasible sets
with the usual split:

- $\{3,5,7,9,11,15,27,45\}$, $\sum 1/m \approx 1.0041 \ge 1$: minimum
  uncovered $44 \cdot 60 = 2640$ of $10395$. **Cannot cover.**
- $\{3,5,7,9,11,13,15,27,45\}$, $\sum 1/m \approx 1.0811 \ge 1$: minimum
  uncovered $44 \cdot 720 = 31680$ of $135135$. **Cannot cover.**

## Finding 7 — the $\{3,5\}$ impossibility, proved in Lean for all exponents

*Run `394a26d5` (generation 7, seeded from `8cb4083d`), 10 confirmed
artifacts across Lean, Z3 and Prolog, two at the slow tier.*

The first result in this campaign about **infinitely many modulus sets at
once**, and the first proved rather than enumerated. Six generations had
settled sets one at a time; this one closes a whole prime support.

Apply the density bound not to one modulus set but to the whole divisor
lattice available to a prime support. Every modulus supported on $\{3,5\}$
divides some $3^A 5^B$, and the reciprocals of the entire divisor box sum
geometrically, so *no subset of it can reach density 1*:

$$\sum_{\substack{d \mid 3^A5^B \\ d>1}} \frac 1d
  \;=\; \Big(\sum_{a=0}^{A} 3^{-a}\Big)\Big(\sum_{b=0}^{B} 5^{-b}\Big) - 1
  \;<\; \tfrac32 \cdot \tfrac54 - 1 \;=\; \tfrac78 \;<\; 1 .$$

**Theorem** (Lean 4 + Mathlib, machine-checked, no `sorry`). For all
$A, B$ and every finite set $E$ of exponent pairs with $(0,0) \notin E$:

```lean
theorem divisor_subset_recip_sum_lt_78 {A B : ℕ} (E : Finset (ℕ × ℕ))
    (hE : ∀ p ∈ E, p ≠ (0, 0) ∧ p.1 ≤ A ∧ p.2 ≤ B) :
    (∑ p ∈ E, ((1/3 : ℚ) ^ p.1 * (1/5 : ℚ) ^ p.2)) < 7 / 8

theorem no_distinct_3_5_cover {A B : ℕ} {E : Finset (ℕ × ℕ)}
    {α : Type*} [Fintype α] [DecidableEq α]
    (hcard : Fintype.card α = 3^A * 5^B)
    (hE : ∀ p ∈ E, p ≠ (0, 0) ∧ p.1 ≤ A ∧ p.2 ≤ B)
    (C : ℕ × ℕ → Finset α)
    (hC_card : ∀ p ∈ E, ((C p).card : ℚ)
                 = ((3^A * 5^B : ℕ) : ℚ) * ((1/3 : ℚ) ^ p.1 * (1/5 : ℚ) ^ p.2))
    (hcover : ∀ x : α, ∃ p ∈ E, x ∈ C p) :
    False
```

So **no covering system of any kind — however many moduli, however
large — is supported on the primes $\{3,5\}$ alone**, and the uncovered
fraction is at least $1/8$. The proof chains a geometric-sum identity, a
monotonicity bound over subsets of the divisor box, and a pigeonhole union
bound; Z3 independently confirmed the real-arithmetic supremum and Prolog
exhausted the small cases, so the claim carries three differently-shaped
confirmations.

The same argument gives the general criterion: a covering supported on a
prime set $P$ requires

$$\prod_{p \in P} \frac{p}{p-1} \;>\; 2,$$

which for odd $P$ reads

| Prime support | $\prod p/(p-1)$ | Sup density | Verdict |
|---|---|---|---|
| $\{3\}$ | $3/2$ | $1/2$ | impossible; uncovered $\ge 1/2$ |
| $\{3,5\}$ | $15/8$ | $7/8$ | **impossible** (proved above) |
| $\{3,5,7\}$ | $35/16$ | $19/16$ | density permits — where the problem lives |

**Novelty, stated honestly.** As mathematics this is elementary and
certainly known: it amounts to observing that the reciprocals of the
$\{3,5\}$-smooth numbers above 1 sum to $7/8$. What is worth recording is
that the harness found and formally proved it unprompted by any literature,
and that it explains the campaign's own data — every entangled set
enumerated above is $\{3,5\}$- or $\{3,5,7\}$-supported.

**And it is not sharp.** The bound guarantees only $1/8 = 0.125$ uncovered
for $\{3,5\}$-supported sets, while every exhaustive computation in the
table below lands between $0.27$ and $0.38$. That gap is where new
mathematics would be: a floor the density argument cannot see. Together
with the $\{3,5,7\}$ row, where density stops deciding entirely, it is what
the remaining generations are pointed at.

## Finding 8 — into $\{3,5,7\}$, where density stops deciding

*Run `94452de2` (generation 9, seeded from `74abc803`), 8 confirmed
artifacts, one at slow tier. The first run with the evolutionary loop:
`forked: 2`, the first non-zero fork count in the campaign.*

Every earlier finding was $\{3,5\}$-supported, where Finding 7 shows density
alone decides. This one is on $\{3,5,7\}$ — the first support where the
density bound permits a covering — and it is settled by a **combinatorial**
argument rather than by enumeration or by density.

**Theorem.** $S = \{3,5,7,9,15,21,25,27,35\}$ is density-feasible
($\sum 1/m = \tfrac{4759}{4725} \approx 1.0072 \ge 1$) and **cannot cover**.

**Proof.** Split $S$ by divisibility by 3. The moduli coprime to 3,
$D = \{5,7,25,35\}$, supply density
$\tfrac15+\tfrac17+\tfrac1{25}+\tfrac1{35} = \tfrac{72}{175}$, so each of the
three residue classes mod 3 needs relative density at least
$1 - \tfrac{72}{175} = \tfrac{103}{175}$ from elsewhere. (That $D$ cannot
cover the class $1 \bmod 3$ on its own was confirmed independently in Z3 and
in Prolog.)

The remaining moduli $\{3,9,15,21,27\}$ are each divisible by 3, so each
lives **entirely inside one** mod-3 layer, contributing relative density
$1, \tfrac13, \tfrac15, \tfrac17, \tfrac19$ respectively to whichever layer
it lands in. Covering therefore requires a *partition* of these five into
three layers with every layer reaching $\tfrac{103}{175}$.

The total available is $\tfrac{563}{315} \approx 1.787$ and the total
required is $3 \cdot \tfrac{103}{175} \approx 1.766$, so **density permits
it**. No partition achieves it: whichever layer receives the modulus 3 is
covered outright, and the other two must split
$\{\tfrac13,\tfrac15,\tfrac17,\tfrac19\}$, total $\approx 0.787$, between
them while needing $\approx 1.177$. Machine-checked in Lean at slow tier,
over all partitions. $\blacksquare$

**Why this one matters.** The set has $3 \cdot 5 \cdot 7 \cdot 9 \cdot 15
\cdot 21 \cdot 25 \cdot 27 \cdot 35 \approx 1.7 \times 10^{9}$ residue
assignments — far past exhaustive search, which every earlier finding relied
on. The obstruction is that prime-power moduli are **indivisible**: a modulus
divisible by 3 must commit its whole density to a single layer, and
granularity, not scarcity, is what defeats the covering. That is an argument
about structure rather than counting, it works where density is satisfied,
and it is the first tool this campaign has that could plausibly scale to the
general $\{3,5,7\}$ case.

## Finding 9 — every odd covering needs at least four primes

*Runs `98d0423e` (generation 12) and `6f6704f4` (generation 13), both
unseeded. The argument's shape is generation 13's, artifact 345; the
extension to all $N$ and the sweep over the remaining supports were done by
hand outside the run. Every constant below was re-derived independently in
exact rational arithmetic, not through the run's engines.*

Finding 8 closed one nine-element $\{3,5,7\}$-supported set by indivisibility
and predicted the tool would scale. It does — to the whole support, and then
to every support with three primes or fewer.

### The partition, stated once

Fix $q$ and split $\mathbb{Z}$ into the $q$ classes mod $q$. For a class $c$
and a modulus $m$, put $g = \gcd(m,q)$. The congruence $a_m \bmod m$ meets
class $c$ with relative density $g/m$ when $a_m \equiv c \pmod g$, and $0$
otherwise. Covering requires every class to reach relative density $1$.

Two facts about $q = 15$ decide everything below.

**No counting proof can exist.** Aggregate the moduli by $g$ and let the
masses be real. Over *all* $3,5,7$-smooth odd $m>1$ the group totals are
$\tfrac16$ ($g{=}1$), $\tfrac74$ ($g{=}3$), $\tfrac{35}{24}$ ($g{=}5$),
$\tfrac{35}{16}$ ($g{=}15$). That relaxation is **feasible** — a witness
exists with eleven of the fifteen classes tight at exactly $\tfrac56$. So no
LP, density or Farkas certificate can ever prove $\{3,5,7\}$ impossible;
confirmed dually by the non-existence of a weighted contradiction over the
fifteen class inequalities. Any proof must use integrality.

**Integrality decides it.** The moduli $3$, $5$ and $15$ have $g/m = 1$
exactly, so each *fully covers* its classes — five, three and one of the
fifteen. Fix their residues $(r_3, r_5, y)$: the $s$ classes they miss
receive nothing from them and must reach density $1$ from the tail alone.
The tail's reach is bounded by

$$s F_1 + \max_3 \cdot A_3 + \max_5 \cdot A_5 + A_{15},$$

where $A_g$ is the tail's total $g$-mass and $\max_3, \max_5$ are the largest
numbers of missed classes inside a single mod-3 resp. mod-5 block — each tail
modulus can do no better than aim at the fullest block.

**Theorem.** For each support $\{3,5,q\}$ with $q \in \{7,11,13\}$, the
mod-15 layered condition fails in all $225$ cases $(r_3,r_5,y)$, over the
**full infinite** smooth modulus set. Hence no odd covering system has any of
these supports.

| support | $F_1$ | $A_3$ | $A_5$ | $A_{15}$ | min slack |
| ------- | ----- | ----- | ----- | -------- | --------- |
| $\{3,5,7\}$  | $1/6$  | $3/4$   | $11/24$ | $19/16$ | $35/48$   |
| $\{3,5,11\}$ | $1/10$ | $13/20$ | $3/8$   | $17/16$ | $151/80$  |
| $\{3,5,13\}$ | $1/12$ | $5/8$   | $17/48$ | $33/32$ | $209/96$  |

Adding moduli only adds density, so failure over the infinite set implies
failure for every finite subset. The tightest case throughout is
$r_3 = 0, r_5 = 0, y = 1$ with $s = 7$; for $\{3,5,7\}$ the tail reaches
$\tfrac{301}{48} \approx 6.27$ against $7$ needed.

**Corollary (four primes).** Every odd covering system has at least four
distinct odd primes in its support. A support $P$ needs
$\prod_{p \in P} p/(p-1) \ge 2$, since $\sum 1/m$ over $P$-smooth $m>1$ is
$\prod p/(p-1) - 1$. No one- or two-prime odd support reaches $2$. For three
primes: $3 \in P$ (without it the best is $\{5,7,11\}$ at $1.604$), $5 \in P$
(without it, $\{3,7,11\}$ at $1.925$), and then
$\tfrac{15}{8}\cdot\tfrac{q}{q-1} \ge 2$ forces $q \le 16$ — leaving exactly
$\{3,5,7\}, \{3,5,11\}, \{3,5,13\}$, all three killed above. $\blacksquare$

**Standing on the literature.** Not checked against MathSciNet, and it should
be before anyone calls it new. The square-free case is already *solved* —
Balister, Bollobás, Morris, Sahasrabudhe and Tiba show there is no odd
square-free covering at all, superseding the Simpson–Zeilberger ($\ge 18$
primes) and Guo–Sun ($\ge 22$) bounds. The general case is open; the known
partial results there are Hough–Nielsen (every distinct covering has a
modulus divisible by $2$ or $3$, which independently forces $3 \in P$ above)
and Balister et al. (an odd covering's lcm is divisible by $9$ or $15$ —
which does *not* exclude $\{3,5,7\}$, so the result above is independent of
it). A web search found no statement of the four-primes bound, but that is
weak evidence: it is elementary relative to the field's machinery, and the
"9 or 15" result comes from exactly this kind of small-support analysis.

**Corrections to earlier generations.** Generation 11 shipped three confirmed
artifacts asserting that no subcollection of $P_{500}$, $P_{600}$, $P_{1000}$
satisfies the mod-3 layered condition. All three gave $3$-divisible moduli
coefficient $L/m$ where the condition requires $3L/m$, understating them
threefold; regenerated with the right coefficient, all three are **SAT**. The
claims are false and are retracted. The true mod-3 threshold is $N \le 342$,
flipping at $343 = 7^3$.

## Frontier table

| Odd distinct modulus set   | $\sum 1/m$              | Density-feasible | Max coverage / $L$      | Verdict                  |
| -------------------------- | ----------------------- | ---------------- | ----------------------- | ------------------------ |
| $\{3,5,7,9\}$              | $248/315 < 1$           | no               | $195/315$ (exact)       | cannot cover (trivially) |
| $\{3,5,7,9,21\}$           | $263/315 < 1$           | no               | $207/315$ (exact)       | cannot cover (trivially); building-block $u_E$ |
| $\{3,5,7,9,11,13,15\}$     | $46027/45045 \ge 1$     | **yes**          | $32805/45045$ (exact)   | **cannot cover**         |
| $\{3,5,7,9,11,15,17\}$     | $\approx 1.0037 \ge 1$  | **yes**          | $42585/58905$ (exact)   | **cannot cover**         |
| $\{3,5,7,9,11,13,15,17\}$  | $827504/765765 \ge 1$   | **yes**          | $569925/765765$ (exact) | **cannot cover**         |
| $\{3,5,7,9,11,13,15,25\}$  | $\ge 1$                 | **yes**          | $167625/225225$ (exact) | **cannot cover**         |
| $\{3,5,7,9,11,13,15,27\}$  | $\ge 1$                 | **yes**          | $101295/135135$ (exact) | **cannot cover**         |
| $\{3,5,7,9,11,15,25,27\}$  | $53114/51975 \ge 1$     | **yes**          | $38715/51975$ (exact)   | **cannot cover**         |
| $\{3,5,9,11,13,15,17,21,27\}$ | $2348807/2297295 \ge 1$ | **yes**       | $1717455/2297295$ (exact) | **cannot cover**       |
| $\{3,5,7,9,11,15,27,45\}$ | $\approx 1.0041 \ge 1$  | **yes**          | $7755/10395$ (exact)    | **cannot cover**         |
| $\{3,5,7,9,11,13,15,27,45\}$ | $\approx 1.0811 \ge 1$ | **yes**       | $103455/135135$ (exact) | **cannot cover**         |
| $\{3,5,7,9,15,21,25,27,35\}$ | $4759/4725 \ge 1$   | **yes**          | — (partition argument)  | **cannot cover** ($\{3,5,7\}$-supported) |
| all $3,5,7$-smooth odd $m>1$   | $19/16 \ge 1$   | **yes**          | — (mod-15 integrality)  | **cannot cover** (Finding 9) |
| all $3,5,11$-smooth odd $m>1$  | $17/16 \ge 1$   | **yes**          | — (mod-15 integrality)  | **cannot cover** (Finding 9) |
| all $3,5,13$-smooth odd $m>1$  | $33/32 \ge 1$   | **yes**          | — (mod-15 integrality)  | **cannot cover** (Finding 9) |
| all $3,5,11,13$-smooth odd $m>1$ | $\ge 1$       | **yes**          | — (mod-15 integrality, slack $283/960$)  | **cannot cover** |
| all $3,7,11,13$-smooth odd $m>1$ | $\ge 1$       | **yes**          | — (mod-21 integrality, slack $3313/1440$) | **cannot cover** |

## Finding 10 — the $\{3,5,7,q\}$ family is closed except at $q=11$

The four-prime supports are infinite, so they cannot be enumerated. They can
be handled uniformly instead, and the mod-$105$ bound does it.

**The bound is monotone in the fourth prime.** For support $\{3,5,7,q\}$ every
constant depends on $q$ only through $T = q/(q-1)$: the tail is
$F_1 = 1/(q-1)$ and $A_g = T\,C_g - 1$ with
$C_g = \prod_{p \mid g} \tfrac{p}{p-1}$, so
$C_3 = \tfrac32, C_5 = \tfrac54, C_7 = \tfrac76, C_{15} = \tfrac{15}{8},
C_{21} = \tfrac74, C_{35} = \tfrac{35}{24}, C_{105} = \tfrac{35}{16}$. Then

$$S(q) = s\cdot\tfrac{q-2}{q-1} - \sum_g A_g(q)\,M_g - A_{105}(q).$$

As $q$ grows $T$ falls toward $1$, so every $A_g$ falls while $s(q-2)/(q-1)$
rises: $S$ is increasing in $q$. Proved rather than observed — Z3 is given
$s, M_3, \dots, M_{35}, q_1, q_2$ free, the hypotheses $q_2 > q_1 > 1$ and
all counts nonnegative, **and the negation** $S(q_1) > S(q_2)$, and returns
unsat. Since the uncovered-class combinatorics depend only on residues mod
$105$ and $q$ is coprime to $105$, the same $1{,}157{,}625$ assignments serve
every $q$, so the *minimum* over assignments is monotone too.

**$q = 13$ is already positive.** Over all $1{,}157{,}625$ translation-reduced
assignments the minimum scaled slack for $\{3,5,7,13\}$ is $+335$ at
denominator $576$ — that is $\tfrac{335}{576} \approx 0.5816 > 0$ — attained
at $(1,1,2,4)$ with the same data $s=36$, $(M_3,\dots,M_{35}) =
(23,11,7,6,4,2)$ that is worst for $q=11$.

**Therefore $\{3,5,7,q\}$ is infeasible for every prime $q \ge 13$**, and with
$S(11) = -\tfrac{647}{480} < 0$ the single surviving member of the family is
$q = 11$. One computation at $q=13$ plus one monotonicity proof retires an
infinite family.

*Engine-confirmed:* a\#366 (Z3, monotonicity) and a\#367 (Octave, the $q=13$
minimum). Both re-derived independently in exact rational arithmetic: all
eight $q=13$ coefficients $(528,360,204,152,594,516,334,789)$ reproduce, and
a full re-enumeration returns $335$ at the same assignment.

## Finding 11 — the four-prime frontier is finite outside three families

Generation 15. Every density-feasible four-prime support must contain $3$
(largest product without it, $\{5,7,11,13\}$, is $1.7378$) and must contain
$5$ or $7$ — with $3$ present but the next prime at least $11$, the best is
$(3/2)(11/10)(13/12)(17/16) = 1.8992 < 2$. Pushing that further:

**Outside the three dense base triples $\{3,5,7\}, \{3,5,11\}, \{3,5,13\}$,
every density-feasible four-prime support has all primes $< 257$.** Four
exhaustive cases, checked as one unsat disjunction:

| region | bound |
| ------ | ----- |
| smallest prime $\ge 5$ | $(5/4)(7/6)(11/10)(13/12) = 1.7378$ |
| $3$, then $\ge 11$ | $(3/2)(11/10)(13/12)(17/16) = 1.8992$ |
| $3,5$, then $\ge 17$, then $\ge 257$ | $(3/2)(5/4)(17/16)(257/256) = \tfrac{65535}{32768}$ |
| $3,7$, then $\ge 11$, then $\ge 29$ | $(3/2)(7/6)(11/10)(29/28) = 1.99375$ |

The third is one part in $32768$ below $2$, and it is **sharp**: at $d = 251$
the product is $2.000156 > 2$, so $\{3,5,17,251\}$ really is density-feasible.

So the frontier is exactly three infinite families plus a finite list bounded
by $257$. Of the families, $\{3,5,7,q\}$ is closed for $q \ge 13$ by
Finding 10, and both $\{3,5,11,q\}$ and $\{3,5,13,q\}$ collapse by
monotonicity to $q = 7$ — that is, to $\{3,5,7,11\}$ and $\{3,5,7,13\}$.

**But the collapse needs the family's own partition, and for
$\{3,5,13,q\}$ it fails.** At $Q = 195 = 3\cdot5\cdot13$ the minimum slack for
$\{3,5,7,13\}$ is $-1729/576 \approx -3.0017$, at $(r_{15},r_{39},r_{65}) =
(1,1,2)$ with $s = 78$. $\{3,5,7,13\}$ is closed at $Q = 105$, but $Q=105$ is
not available to $\{3,5,13,q\}$ for $q \ne 7$, so that family stays open from
below and needs its threshold found some other way. Re-enumerated
independently: $-1729$ at the same assignment.

*Also confirmed, but not new:* no subset of the squarefree divisors of $1155$
reaches reciprocal sum $1$ — the total is $1149/1155 = 383/385$. True, and it
means a covering on $\{3,5,7,11\}$ needs non-squarefree moduli, but it is a
special case of Balister–Bollobás–Morris–Sahasrabudhe–Tiba, who rule out odd
squarefree coverings entirely. Generation 15 shipped it as its final answer,
which overstates what it is.

## The four-prime frontier

Finding 9 retires every support with three primes or fewer, so individual
finite sets inside them are no longer worth settling. Four-prime supports are
the frontier, and unlike three primes they are an **infinite** family:
$\{3,5,7,q\}$ has $\prod p/(p-1) = \tfrac{35}{16}\cdot\tfrac{q}{q-1} > 2$ for
every prime $q$. Enumeration cannot close them. Any argument has to be uniform
in the fourth prime.

Two structural facts survive from three primes. $3$ lies in every
density-feasible four-prime support — the largest product without it is
$\{5,7,11,13\}$ at $1.7378 < 2$. But $5$ no longer must: $\{3,7,11,13\}$
reaches $2.0854$.

Running the Finding 9 bound over four-prime supports at every $q = pp'$ built
from the support gives:

| Support | best $q$ | min slack | |
| ------- | -------- | --------- | - |
| $\{3,5,11,13\}$ | $15$ | $+283/960$   | closed |
| $\{3,7,11,13\}$ | $21$ | $+3313/1440$ | closed |
| $\{3,5,7,q\},\ q \ge 13$ | $105$ | $\ge +335/576$ | closed — Finding 10 |
| $\{3,5,7,11\}$  | $105$ | $-647/480$   | **open** |

The $q=15$ column that once showed $\{3,5,7,13\}$ at $-553/576$ is superseded:
the bound is much stronger at $q=105$, where that support is $+335/576$.
$\{3,5,7,11\}$ is negative at both.

with $F_1 = 17/60$, $A_3 = 37/40$, $A_5 = 29/48$, $A_{15} = 45/32$ for
$\{3,5,7,11\}$. The worst assignment is $(r_3,r_5,y) = (0,0,1)$, $s=7$,
$\max_3 = 4$, $\max_5 = 2$ — the same one that is tightest for every support
the argument does settle.

$\{3,5,7,11\}$ is the hardest member of the $\{3,5,7,q\}$ family, because
$F_1 = \tfrac{7}{6}\cdot\tfrac{q}{q-1} - 1$ is largest at $q=11$ and decreases
to $1/6$. A bound monotone in $F_1$ that closes $q=11$ closes the whole
infinite family at once. That is the shape of the result worth having.

**$q = 105$ does not close $\{3,5,7,11\}$ either.** Its unit-weight moduli are
$3,5,7,15,21,35,105$ — a much richer set of atoms than the three at $q=15$ —
and a brute-force sweep is $3\cdot5\cdot7\cdot15\cdot21\cdot35\cdot105 =
361{,}675{,}125$ assignments, cut to $1{,}157{,}625$ by fixing
$r_3 = r_5 = r_7 = 0$, which translation invariance mod $105$ permits without
loss. The constants are

$$F_1 = \tfrac{1}{10},\quad A_3 = \tfrac{13}{20},\quad A_5 = \tfrac{3}{8},
\quad A_7 = \tfrac{17}{60},\quad A_{15} = \tfrac{17}{16},\quad
A_{21} = \tfrac{37}{40},\quad A_{35} = \tfrac{29}{48},\quad
A_{105} = \tfrac{45}{32}$$

— note $11$ enters *only* here, since it is coprime to $105$ and so appears in
no class and no block. Scaled by $480$ they are
$48, 312, 180, 136, 510, 444, 290, 675$, and the minimum slack is

$$-\tfrac{647}{480} \approx -1.3479 \quad\text{at } (r_{15},r_{21},r_{35},r_{105}) = (1,1,2,4),$$

with $s = 36$ and $(M_3,M_5,M_7,M_{15},M_{21},M_{35}) = (23,11,7,6,4,2)$.

That is *worse* than $q=15$'s $-623/480$. So a finer $q$ is not automatically
stronger for this bound — among semiprimes $q=15$ also beats $21$, $33$, $35$,
$55$ and $77$ for $\{3,5,7,11\}$, by a wide margin. Refining the partition is
a dead end here; the bound itself has to change.

The bound is also crude in a specific, fixable way. It charges every $g=3$
modulus the full $\max_3$, as though each could serve the fullest block; in
truth they compete, and a modulus assigned to a sparser block contributes
less. Accounting for the assignment rather than taking the maximum for every
modulus is strictly stronger and may close $\{3,5,7,11\}$ without needing
$q=105$ at all.

What will *not* work, and is now proved rather than suspected: any argument
that only counts. The aggregated real relaxation is feasible in the limit
for $\{3,5,7\}$, so a density or LP bound cannot decide even the support
already settled. Expect the same one step up.

## Provenance

- Runs: `c45ac428-182f-4cd3-876e-686acc1e9f2c` (2026-08-06),
  `b84d2263-d8f6-4144-81bd-f5ef0e3b6dd1` (2026-08-07),
  `61de2075-6413-458d-aa03-667e56aea459`,
  `c5dcc35f-3e05-45e0-bc9f-1a9e8d76fee4`,
  `7f4af6b7-f494-4303-9fac-39b5927e3032`,
  `8cb4083d-8eec-4b2e-be7d-8152a86f5a4a`,
  `394a26d5-5270-4bc8-a513-28ace2e7ae08`,
  `74abc803-705c-486b-8f85-baaad08c6c8d`,
  `94452de2-0dfd-442a-bc4d-596ede4a1fc9` (2026-08-07), each after the first
  seeded from the run before it, all `deepseek-v4-flash` with artifact
  sharing on, beam width 2 through generation 4 and 3 thereafter.
- Generations 10–15 (2026-08-07/09): `05ecb88a-52d7-4d72-acaf-d389aa367112`,
  `90336412-b135-422c-a9cf-116064890e12`,
  `98d0423e-ddf6-46a5-97a6-b6493ec82e03`,
  `6f6704f4-00da-4a48-8db6-49bb56ca8ddb`,
  `21104871-49a9-4b2c-b7c4-3f6959795e17`,
  `a6db156b-8e6d-4e4e-8d86-80e61f3713c8`. Generations 12 onward were run
  **unseeded**: generation 11 left false artifacts marked confirmed (see the
  corrections in Finding 9), and `seed_run` imports on `claim_status =
  'confirmed'`, so seeding would have carried them forward as established.
  Generation 11's row still reads `running` in the journal — the process died
  without the supervisor noticing, which is the failure the run-error
  journalling now catches. Clearing that row needs a direct write to
  `veriframe.sqlite3` and has not been done.
- The four-prime frontier table is hand-computed, in exact rational
  arithmetic, by the same program that reproduces Finding 9's constants and
  all three of its slacks exactly. It has not been through the engines.
- The $q=105$ result is **also engine-confirmed**, by generation 14, as a
  pair: a\#352 (Prolog) verifies the combinatorics — that $(1,1,2,4)$ leaves
  $36$ uncovered classes with maxima $(23,11,7,6,4,2)$ — and a\#355 (Z3)
  derives all eight constants from $P$ and asserts `slack >= 0`, which comes
  back unsat. Both agree with the hand computation to the digit.
- Getting there took three rejected artifacts and a human intervention, and
  the reason is worth recording. The first attempts — Octave and Prolog,
  both with the right number — hardcoded the eight scaled constants. Since
  $11$ is coprime to $105$ it enters the argument *only* through those
  constants, so an artifact that states them computes a number for an
  unstated support: the $\{3,5,7\}$ version of the same program is identical
  in shape and gives a false claim. The reviewer refused both on exactly that
  ground and was right to. This is the a\#344 defect one level subtler — a
  number carried in from a derivation the artifact does not contain — and it
  is worth knowing that a correct number is not the same thing as a verified
  one. a\#355 fixes it by computing `T_all = (3*5*7*11)/(2*4*6*10)` in the
  encoding, which is the first place the fourth prime appears at all.
- Every claim above sits in the run journal as a confirmed artifact with
  the exact engine code that verified it (`GET /v1/runs/:id/journal`).
  Cross-run continuation can import them with `seed_run`.
- **Finding 9 is the exception, deliberately.** Its constants and all $225$
  cases were re-derived by hand in exact rational arithmetic rather than
  taken from the journal. Generations 12 and 13 each produced confirmed
  artifacts that were false — a Prolog goal posting its constraints inside
  `findall/3`, and another inside `forall/2`, both of which undo constraint
  posts on completion, so the goals succeeded having enforced nothing. A
  third asserted one class constraint for a claim about fifteen. Nothing in
  the harness caught any of them at the time. Treat a confirmed artifact as
  a lead, not a result, until its encoding has been read.
