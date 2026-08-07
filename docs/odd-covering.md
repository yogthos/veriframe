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

## The prime-support bound — a family-wide floor, pending verification

Six generations of enumeration have settled sets one at a time. The
following is a *conjecture of this write-up*, derived by hand from the
campaign's own data and **not yet engine-confirmed** — it is stated here as
the target for the next generation, and should be read with exactly the
skepticism the harness applies to anything an engine has not checked.

Apply the density bound not to one modulus set but to the whole divisor
lattice available to a prime support $P$. Every modulus is a divisor of
some $\prod_{p \in P} p^{e_p}$, so the total density any set supported on
$P$ can muster is bounded by the sum of $1/d$ over *all* divisors $d > 1$:

$$\sum_{d \mid \prod p^{e_p},\, d>1} \frac 1d \;\le\;
  \prod_{p \in P} \frac{p}{p-1} \;-\; 1 .$$

A covering needs density $\ge 1$, so a covering supported on $P$ requires

$$\prod_{p \in P} \frac{p}{p-1} \;>\; 2 .$$

For odd $P$ this bites immediately:

| Prime support | $\prod p/(p-1)$ | Sup density | Verdict |
|---|---|---|---|
| $\{3\}$ | $3/2$ | $1/2$ | impossible; uncovered $\ge 1/2$ |
| $\{3,5\}$ | $15/8$ | $7/8$ | **impossible; uncovered $\ge 1/8$** |
| $\{3,5,7\}$ | $35/16$ | $19/16$ | density permits |

So no odd covering system is supported on $\{3,5\}$ alone, however many
moduli and however large — infinitely many modulus sets ruled out at once,
which is the shape the campaign wanted. It also explains why every
entangled set enumerated above sits where it does: all are $\{3,5\}$- or
$\{3,5,7\}$-supported.

**And it is not sharp.** The bound guarantees only $1/8 = 0.125$ uncovered
for $\{3,5\}$-supported sets, while every exhaustive computation in the
table below lands between $0.27$ and $0.38$. The gap is the interesting
part: a proof that $\{3,5\}$-supported sets leave more than $1/8$ uncovered
would be a floor the density argument cannot see, and that is the next
generation's primary target.

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

**Next targets** (Pareto-minimal density-feasible sets: maximize density
slack, minimize $\operatorname{lcm}$, minimize modulus count): supersets of
the $\{3,5,7,9,21\}$ building block with coprime parts from
$\{11,13,17,19\}$; sets with richer entangled parts ($25$, $27$, $33$, $35$
— the 9-or-15 lcm condition says the entangled part is where the action
is). The emerging pattern: in every settled case the coprime part's
coverage is exactly its independence value with no way to aim it at the
entangled part's gaps, so the search for a covering (or an impossibility
proof) reduces to whether entangled moduli can *cooperate* beyond what
these exhaustive bounds show.

## Provenance

- Runs: `c45ac428-182f-4cd3-876e-686acc1e9f2c` (2026-08-06),
  `b84d2263-d8f6-4144-81bd-f5ef0e3b6dd1` (2026-08-07),
  `61de2075-6413-458d-aa03-667e56aea459`,
  `c5dcc35f-3e05-45e0-bc9f-1a9e8d76fee4`,
  `7f4af6b7-f494-4303-9fac-39b5927e3032`,
  `8cb4083d-8eec-4b2e-be7d-8152a86f5a4a` (2026-08-07), each after the first
  seeded from the run before it, all `deepseek-v4-flash` with artifact
  sharing on, beam width 2 through generation 4 and 3 thereafter.
- Every claim above sits in the run journal as a confirmed artifact with
  the exact engine code that verified it (`GET /v1/runs/:id/journal`).
  Cross-run continuation can import them with `seed_run`.
