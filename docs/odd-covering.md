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

## Frontier table

| Odd distinct modulus set | $\sum 1/m$ | Density-feasible | Max coverage / $L$ | Verdict |
|---|---|---|---|---|
| $\{3,5,7,9\}$ | $248/315 < 1$ | no | $195/315$ (exact) | cannot cover (trivially) |
| $\{3,5,7,9,11,13,15\}$ | $46027/45045 \ge 1$ | **yes** | $32805/45045$ (exact) | **cannot cover** |

**Next targets** (Pareto-minimal density-feasible sets: maximize density
slack, minimize $\operatorname{lcm}$, minimize modulus count): replace 13
or 11 with 21, 25, 27, 33, 35 and neighbours; sets mixing more powers of 3
and 5 (the 9-or-15 lcm condition says the entangled part is where the
action is). Each verified set extends the empirical wall the conjecture
lives behind.

## Provenance

- Runs: `c45ac428-182f-4cd3-876e-686acc1e9f2c` (2026-08-06),
  `b84d2263-d8f6-4144-81bd-f5ef0e3b6dd1` (2026-08-07), both
  `deepseek-v4-flash`, beam width 2, artifact sharing on.
- Every claim above sits in the run journal as a confirmed artifact with
  the exact engine code that verified it (`GET /v1/runs/:id/journal`).
  Cross-run continuation can import them with `seed_run`.
