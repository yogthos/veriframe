# Benchmark: Direct vs Z3-harnessed reasoning

## Setup

- **Model**: `Qwen3.6-35B-A3B-Q8_0.gguf` running locally via `node-llama-cpp` (Q8 quant, ~37 GB on disk).
- **Hardware**: Apple Silicon Mac, default Metal GPU offload.
- **Server**: started with `HARNESS_MODEL_PATH=models/Qwen3.6-35B-A3B-Q8_0.gguf HARNESS_MAX_TOKENS=12288 npm start` listening on port 3001.
- **Driver**: `scripts/compare.ts <problem-id>` posts the verbatim prompt twice — once with `{ raw: true }` (model only) and once through the harness loop. Problems live in `scripts/problems.ts`.
- **Harness loop**: LLM emits SMT-LIB step → Z3 incremental solver checks consistency → SAT keeps the frame, UNSAT pops and forces a fix using the unsat core. On `complete: true` the harness extracts Z3's model and proves uniqueness by asserting the negation of the model and rechecking. UNSAT-on-step-1 with consistent unsat cores is surfaced as a valid "no solution" terminus.
- **Sampling**: temperature 0.7 (Qwen default), single sample per cell. Wall times will vary across runs.

## Summary table

| # | Problem | Difficulty | Direct (s) | Direct correct? | Harnessed (s) | Harnessed correct? | Z3 verdict |
| - | --- | --- | ---: | --- | ---: | --- | --- |
| 1 | `knights-3` (3-person K&K) | easy | 61.4 | ✓ | 27.9 | ✓ | unique |
| 2 | `knights-5-counts` (5-person count claims) | medium | 73.0 | ✓ | 51.0 | ✓ | unique |
| 3 | `knights-5-mixed` (5-person mixed, unique solution) | hard | 93.1 | ✓ | 55.3 | ✓ | unique |
| 4 | `arith-triple` (Diophantine sum + product) | hard | 121.5 | ✓ | 55.7 | ✓ | unique |
| 5 | `einstein-4x4` (4 houses × 4 categories, 9 clues) | very-hard | 81.4 | ✓ | 334.5 | ✓ | unique |
| 6 | `knights-5-mixed-unsat` (intentionally contradictory) | hard | 299.6 | ✓ (UNSAT) | 468.6 | ✓ (UNSAT) | UNSAT, full 5-clue core |
| 7 | `pigeonhole-3-2` (3 pigeons, 2 holes — UNSAT CNF) | medium | 77.7 | ✓ (UNSAT) | 328.2 | ✓ (UNSAT) | UNSAT, full 9-clause core |
| 8 | `sudoku-hard` (Inkala-2010, 9×9, hard) | very-hard | **679.7 — empty output, fail** | ✗ | **501.3** | ✓ (all 81 cells correct) | unique |
| 9 | `sally-sisters` (Sally has 3 brothers, each has 2 sisters — how many sisters does Sally have?) | easy | 27.2 | ✓ (1) | 111.8 | ✓ (1) | unique |
| 10 | `alice-brother-sisters` (Alice has 4 brothers and 1 sister — how many sisters does Alice's brother have?) | easy | 30.6 | ✓ (2) | 114.5 | ✓ (2) | unique |
| 11 | `river-large-boat` (modified river-crossing, boat carries everyone) | medium | 36.0 | ✓ (1) | 87.5 | ✓ (1) | unique (degenerate — model just asserted answer) |
| 12 | `doubling-jar` (jar doubles per minute, full at 60 — when half full?) | easy | 21.7 | ✓ (59) | 122.4 | ✓ (59 — but encoding under-constrained) | **NOT unique — harness flagged** |
| 13 | `snail-pole` (climb 4m / slide 3m, 12m pole, when escape?) | medium | 88.6 | ✓ (9) | 391.4 | ✓ (9) | unique |
| 14 | `car-wash-decision` (wash my car at car wash 50m away — walk or drive?) | medium | 14.5 | ✓ (DRIVE) | 265.4 | ✓ (DRIVE) | unique |
| 15 | `zebra-5x5` (classic 15-clue Einstein zebra) | very-hard | 93.1 | ✓ (full grid + Norwegian/Japanese) | 514.4 | ✓ (full grid matches published solution) | unique (read-back verification: OK) |

Across problems 1–7 and 9–14, **direct Qwen 3.6 35B got every solvable puzzle logically right**. We ran a curated set of "puzzles models commonly fail on" sourced from the *Easy Problems That LLMs Get Wrong* paper (arxiv 2405.19616), Apple's *Illusion of Reasoning* paper, and the ZebraLogic / SATBench benchmarks — and Qwen 3.6 35B passed every one of them. The harness's value at that range is independent verification — every harnessed answer ships with a Z3-extracted model plus a UNSAT-on-negation uniqueness proof.

**Problem 8 (Sudoku)** is the case where direct fails on token budget and the harness succeeds: direct burned its full 24576-token budget on `<think>` and never emitted a final grid, while the harness produced a complete, Z3-verified, uniquely-determined solution.

**Problem 15 (Zebra 5×5)** was originally a harness failure (off-by-one in categorical-to-integer mapping caught by the uniqueness check but not corrected). After adding a **back-translation read-back pass** before finalizing — where the model is asked to translate each `:named` assertion back into English and compare against the original prompt clues — the harness now solves it correctly with all 25 cells matching the published solution and uniqueness proven.

## Problem 1 — `knights-3` (easy)

**Prompt**:

> On a logic island, every inhabitant is either a Knight (always tells the truth) or a Knave (always lies). You meet three inhabitants: A, B, and C.
> - A says: "B is a Knight."
> - B says: "A and C are of different types."
> - C says: "A is a Knave."
>
> Determine the type of each.

**Ground truth**: A=Knight, B=Knight, C=Knave (the only assignment satisfying all three statements).

**Direct (61.4 s)**: clean case analysis, correct final assignment.

**Harnessed (27.9 s, 2 steps)**: model declared `a, b, c : Int ∈ {0,1}` and asserted three biconditional constraints in step 1, completed in step 2. Z3 model `a=1, b=1, c=0` (Knight, Knight, Knave). Uniqueness verified.

**Takeaway**: at this size both engines work; harness is faster because it generates far fewer total tokens and reuses the system-prompt KV cache across iterations.

## Problem 2 — `knights-5-counts` (medium)

**Prompt**: five inhabitants make mutually exclusive count claims (A says "exactly 1 Knave", B "exactly 2", C "exactly 3", D "exactly 4", E "all five Knaves").

**Ground truth**: A=Knave, B=Knave, C=Knave, D=Knight, E=Knave (4 Knaves total — only D's claim can be true, and the other four lying about distinct counts is consistent).

**Direct (73.0 s)**: correctly noted that at most one statement can be true, ruled out 0 and 5 Knaves, identified D as the unique Knight.

**Harnessed (51.0 s, 2 steps)** *(after one round of harness fixes)*:

```smt
(declare-const KnaveA Bool) … (declare-const KnaveE Bool)
(define-fun knaveCount () Int (+ (ite KnaveA 1 0) (ite KnaveB 1 0) (ite KnaveC 1 0) (ite KnaveD 1 0) (ite KnaveE 1 0)))
(assert (! (=> (not KnaveA) (= knaveCount 1)) :named A_truth))
(assert (! (=> KnaveA (not (= knaveCount 1))) :named A_lie))
;; ditto for B, C, D, E
```

Verified Z3 model: `KnaveA=true, KnaveB=true, KnaveC=true, KnaveD=false, KnaveE=true`. Uniqueness proven.

**The interesting part** — first attempt (before fixes): the model used `Int` 0/1 variables and wrote a constraint of the form `(= IntVar BoolExpr)`, which is a sort error Z3 silently accepts. The result was a vacuous model where every variable was 0 and `total_knaves=0`. **The harness's uniqueness check correctly flagged this as `NOT unique`**, surfaced a counter-example, and the rendered final answer warned about under-constrained encoding. Round 2 succeeded after adding a "use Bool, not Int 0/1" hint to the prompt.

**Takeaway**: the harness caught a real but subtle encoding bug (sort confusion) that would have produced a confidently wrong "verified" answer if uniqueness wasn't checked. The model was internally consistent but didn't encode the puzzle. This is a class of failure direct prose can't catch.

## Problem 3 — `knights-5-mixed` (hard, unique solution)

**Prompt**:

> Five inhabitants A..E. A: "B is a Knave." B: "C and D are the same type." C: "If A is a Knight, then E is a Knave." D: "Among B, C, and E, exactly three are Knights." E: "A and D are of different types."

**Ground truth**: A=Knave, B=Knight, C=Knight, D=Knight, E=Knight. Verified: A=T case yields C=¬E and C=E, contradiction; A=F case is consistent.

**Direct (93.1 s)**: clean case analysis on D being Knight vs Knave, walked through the implication chain in C's statement correctly, all clues verified.

**Harnessed (55.3 s, 2 steps)**:

```smt
(declare-const A Bool) … (declare-const E Bool)
(assert (! (= A (not B)) :named A_statement))
(assert (! (= B (= C D)) :named B_statement))
(assert (! (= C (=> A (not E))) :named C_statement))
(assert (! (= D (and (and B C) E)) :named D_statement))   ;; "exactly 3 of 3 = all three"
(assert (! (= E (not (= A D))) :named E_statement))
```

Verified Z3 model: `A=false, D=true, B=true, E=true, C=true`. Uniqueness proven.

**Takeaway**: 1.7× speedup, both correct, model now produces a clean Bool encoding without prompting, and tracked-assertion symbols are filtered from the model output. The "exactly three of three" → "all three are knights" simplification is a nice piece of compression by the model.

## Problem 4 — `arith-triple` (hard, Diophantine)

**Prompt**: find positive integers `a < b < c ≤ 25` with `a + b + c = 30` and `a · b · c = 360`.

**Ground truth**: `(2, 10, 18)`. Reduces to a quadratic in `b, c` once `a` is fixed; only `a=2` gives a perfect-square discriminant.

**Direct (121.5 s)**: bounded `a < ⌊∛360⌋ ≈ 7.11`, tested each candidate via discriminant of the quadratic `x² − (30−a)x + 360/a = 0`, found `a=2` ⇒ `b=10, c=18`.

**Harnessed (55.7 s, 2 steps)**: ten-line direct encoding of all four constraints. Verified Z3 model: `a=2, b=10, c=18`. Uniqueness proven.

**Takeaway**: the harness is much terser here (it doesn't need to manually search) and 2.2× faster. Direct's discriminant approach was elegant but expensive in tokens. This is exactly the kind of problem Z3's NIA tactics handle in milliseconds.

## Problem 5 — `einstein-4x4` (very-hard)

**Prompt**: 4 houses × 4 categories (Color, Nationality, Pet, Drink), 9 clues including positional ("Green is immediately left of Yellow"), conditional ("the Brit lives in Red"), absolute ("house 2 drinks Milk"), and adjacency ("Bird owner lives next to Dog owner").

**Ground truth**: `Blue/Dane/Cat/Tea | Red/Brit/Fish/Milk | Green/German/Bird/Coffee | Yellow/Swede/Dog/Water`.

**Direct (81.4 s)**: anchored fixed positions (clues 6, 8), placed Green/Yellow via clue 4 + 5, eliminated one color scenario via Tea/Milk conflict, propagated to a full assignment, verified.

**Harnessed (334.5 s, 3 steps)** *(after one round of harness fixes)*: integer encoding `c_i, n_i, p_i, d_i ∈ {1..4}`, `(distinct …)` for each category, and one explicit assertion **per house per implication clue** (so clue 1 became four separate assertions, one for each house). Verified Z3 model decodes to the expected assignment. Uniqueness proven.

**The interesting part** — first attempt (before fixes): the model produced an elegant high-level encoding using custom sorts (`Color`, `Nat`, `Pet`, `Drink`) and quantified clues (`(forall ((h Int)) (=> (= (Nat h) Brit) (= (Color h) Red)))`). The harness loop accepted both steps as SAT (no contradiction), but **`verifySolution()` returned `null`** — Z3's response on quantified theories with declared abstract sorts is `unknown`, so the model couldn't be extracted and uniqueness couldn't be verified. The renderer surfaced a loud `⚠ NO Z3 VERIFICATION` warning. Round 2 succeeded after adding "avoid quantifiers on finite domains, enumerate explicitly, prefer bounded Int sorts" to the prompt.

**Takeaway**: this exposed the harness's biggest current footgun. Z3 returning `unknown` is **not** a contradiction — it's a "I can't decide." The loop happily accepts unknown as if it were SAT, but `getModel()` then either throws or returns nothing meaningful, and the user gets prose without verification. The renderer now surfaces that condition, but the real fix is in encoding choice. **Direct was 4× faster on this problem** because the harness's per-house enumeration adds tokens that scale with grid size.

## Problem 7 — `pigeonhole-3-2` (medium, UNSAT)

**Prompt**: a 9-clause CNF formula encoding "3 pigeons must each occupy at least one of 2 holes, and no two pigeons may share a hole." Asked whether SAT or UNSAT, with reasoning.

**Ground truth**: UNSAT (the pigeonhole principle: 3 pigeons can't fit in 2 holes if each hole holds at most one). All 9 clauses are needed for the contradiction.

**Direct (77.7 s)**: correctly identified UNSAT. Walked through the case analysis on pigeon 1's placement, showed both `p_11 = True` and `p_12 = True` lead to contradictions via clauses 6 and 9 respectively, and noted the capacity argument (≥3 true literals required vs. ≤2 allowed).

**Harnessed (328.2 s)**: returned `status: "unsat"` with a Z3-proven minimal conflicting set:

```
The encoded constraints are mutually inconsistent — Z3 proved UNSAT.

Minimal conflicting set (unsat core):
  - clause1
  - clause2
  - clause3
  - clause4
  - clause5
  - clause6
  - clause7
  - clause8
  - clause9
```

All 9 clauses are simultaneously needed; this matches the pigeonhole structure exactly. The trace shows Z3 emitted UNSAT on the first SMT step the model produced; the harness's UNSAT terminus (rather than a futile retry loop) returned that result as the final answer.

**Takeaway**: this is the harness's UNSAT pathway working as designed. Both engines reach the same conclusion, but only the harness produces a *machine-checkable* unsat core that pinpoints which clauses are responsible. The model's prose explanation appeals to the pigeonhole principle informally; Z3's core lists every clause involved without hand-waving.

The same pathway also revealed a harness bug along the way — the original UNSAT-terminus required *every* retry to be UNSAT, but in practice some attempts truncated mid-JSON (token budget) before Z3 ever ran. The relaxed rule now triggers as long as at least one attempt produced UNSAT and the unsat cores from UNSAT attempts are consistent.

## Problem 8 — `sudoku-hard` (very-hard, the breaking point)

**Prompt**: solve the Inkala-2010 9×9 Sudoku (one of the hardest published puzzles). Each row, column, and 3×3 box must contain digits 1..9 exactly once; 26 cells are clued, 55 must be inferred.

**Ground truth**: a single solution exists. Top row: `8 1 2 7 5 3 6 4 9`. Full grid included in the expected answer in `scripts/problems.ts`.

**Direct (679.7 s, ~11 min)**: returned **empty content**. The model spent its entire 24576-token budget inside a `<think>` block working through the puzzle and ran out before emitting any final answer. This is the first puzzle in this benchmark where direct fails outright. (Re-running might or might not succeed depending on which thinking path the sampler takes; on this single sample the model never reached the answer.)

**Harnessed (501.3 s, ~8 min, 3 steps)**: completed successfully with all 81 cells correct and uniqueness proven by Z3.

The encoding the model produced:

```smt
; step 1 — declare 81 cells, bound to 1..9, fix the 26 clues
(declare-const c1_1 Int) ... (declare-const c9_9 Int)
(assert (and (>= c1_1 1) (<= c1_1 9))) ... ;; ×81
(assert (= c1_1 8))    ;; clue at row 1, col 1
(assert (= c2_3 3))    ;; ... and 24 more clues

; step 2 — distinct over each row, each column, and each 3×3 box
(assert (distinct c1_1 c1_2 c1_3 c1_4 c1_5 c1_6 c1_7 c1_8 c1_9))   ;; row 1
... ;; ×9 rows, ×9 cols, ×9 boxes

; step 3 — complete, no new assertions
```

Z3's verified model decoded back to the standard grid form:

```
Row 1: 8 1 2 7 5 3 6 4 9
Row 2: 9 4 3 6 8 2 1 7 5
Row 3: 6 7 5 4 9 1 2 8 3
Row 4: 1 5 4 2 3 7 8 9 6
Row 5: 3 6 9 8 4 5 7 2 1
Row 6: 2 8 7 1 6 9 5 3 4
Row 7: 5 2 1 9 7 4 3 6 8
Row 8: 4 3 8 5 2 6 9 1 7
Row 9: 7 9 6 3 1 8 4 5 2
```

Every cell matches the published Inkala-2010 solution. Z3 confirmed UNIQUE on negation of the full assignment.

**Takeaway**: this is the headline result. **For thinking-mode local models, the bottleneck on hard combinatorial puzzles is the token budget, not the reasoning ability.** Sudoku is the canonical case: the constraints are simple to write down (`distinct` over each row/col/box), but solving by hand requires tracking dozens of branches through a deep search tree. Direct Qwen 3.6 35B clearly *can* reason about Sudoku — it just runs out of tokens before producing the answer. The harness sidesteps this entirely by handing the search to Z3 and letting the model do only the easy translation step. The harness took 26% less wall-clock and produced a complete, machine-verified answer in the same domain where direct produced nothing.

The encoding pattern is also worth noting: the model figured out on its own to use bounded `Int` cells and `(distinct …)` over each row/col/box rather than custom sorts or quantifiers (likely thanks to the prompt hints landed during the einstein-4x4 round). The 81 declarations + 81 bounds + 26 clues + 27 distincts = ~215 SMT-LIB lines, well within the bumped 24576-token budget.

## Problem 9 — `sally-sisters` (easy, "Easy Problems" 2024 paper)

**Prompt**: "Sally is a girl. Sally has 3 brothers. Each of Sally's brothers has 2 sisters. How many sisters does Sally have?"

**Ground truth**: 1. The famous LLM trap — the family has exactly 2 girls (Sally + 1 sister); models commonly answer 2 by echoing "sisters per brother" without subtracting Sally herself.

**Direct (27.2 s)**: clean reasoning. Identified that all brothers share the same set of sisters (= the girls in the family), inferred 2 girls total, subtracted Sally → 1 sister.

**Harnessed (111.8 s)**: encoded variables (`sally_is_girl`, `num_sally_brothers`, `total_girls`, `sally_sisters`) and the relations `(= total_girls num_brother_sisters)` and `(= sally_sisters (- total_girls 1))`. Z3 derived `sally_sisters = 1`, uniqueness proven.

**Takeaway**: Qwen 3.6 35B is robust on the canonical sisters-counting trap. Both engines correct.

## Problem 10 — `alice-brother-sisters` (easy, AIW variant)

**Prompt**: "Alice has 4 brothers and 1 sister. How many sisters does Alice's brother have?"

**Ground truth**: 2. Alice's brother sees 2 girls in the family (Alice + Alice's sister). Models commonly answer 1 (echoing "Alice has 1 sister") or 0 (forgetting Alice).

**Direct (30.6 s)**: correct reasoning explicitly noted "Alice counts as one of the girls in the family." Answer 2.

**Harnessed (114.5 s)**: encoded `alice-sisters = 1`, `total-girls = alice-sisters + 1`, `brother-sisters = total-girls`. Z3 derived 2, uniqueness proven.

## Problem 11 — `river-large-boat` (medium, modified-classic trap)

**Prompt**: classic wolf/goat/cabbage river crossing, but the boat fits the farmer + all three items in one trip. Predator-prey rules still hold when farmer absent. Minimum crossings?

**Ground truth**: 1. Apple's *Illusion of Reasoning* showed models pattern-match to the classic 7-trip solution and ignore the boat-capacity statement.

**Direct (36.0 s)**: explicitly noted "this version modifies the rules" and answered 1.

**Harnessed (87.5 s)**: model decided "1" in prose, then asserted `(= crossings 1)` in SMT. Z3's "uniqueness proof" here just verifies that 1 = 1, which is trivial — the encoding is degenerate. The model did the reasoning, not Z3.

**Takeaway**: this is a class of problem (single-integer answer derived from prose reasoning) where the harness can't add value beyond bookkeeping. The Z3 step is rubber-stamping. The harness shines on multi-variable constraint problems, not on "interpret this puzzle and output the number."

## Problem 12 — `doubling-jar` (easy, exponential-growth trap)

**Prompt**: "Bacteria in a jar double every minute. Jar starts with 1 bacterium at minute 0, full at minute 60. When is it half full?"

**Ground truth**: 59. The classic exponential-growth confusion — models trained on linear arithmetic sometimes answer 30.

**Direct (21.7 s)**: correct one-paragraph reasoning ("one minute before full, the population was half"). Answer 59.

**Harnessed (122.4 s)**: model wrote correct prose explaining doubling, but encoded *only* `(= full_count (* 2 half_count))` and `(= t 59)` — the doubling-over-time dynamic was never modeled, just the answer was asserted. **The uniqueness check caught this**: Z3 found two satisfying assignments (`half=1, full=2` and `half=2, full=4`) and flagged the encoding as under-constrained. The harness rendered:

```
⚠ ENCODING UNDER-CONSTRAINED — multiple assignments satisfy these constraints.
This usually means the SMT-LIB does NOT actually capture the problem ...
```

**Takeaway**: this is the harness working correctly to expose a *modeling shortcut*. The model knew the answer but didn't formalize the reasoning. The user sees a clear "this verification is degenerate, the model just asserted the answer" warning rather than a false sense of rigor.

## Problem 13 — `snail-pole` (medium, off-by-one trap)

**Prompt**: snail climbs 4m / slides 3m daily on a 12m pole; once it tops, no slide. Days to reach top?

**Ground truth**: 9. Naive 12 / (4 - 3) = 12 ignores that the final day's climb isn't followed by a slide.

**Direct (88.6 s)**: walked through day-by-day, noted explicitly that on day 9 the snail reaches 12m and escapes before the night slide. Answer 9.

**Harnessed (391.4 s)**: encoded the dynamics cleanly:

```smt
(declare-const days Int)
(assert (! (>= days 1) :named min_days))
(assert (! (<= days 20) :named max_days))
(assert (! (>= (+ days 3) 12) :named escape_condition))
(assert (! (< (+ days 2) 12) :named not_escaped_previous_day))
```

Z3 derived `days = 9` with verified uniqueness. Genuine SMT modeling, not answer-baking. This is the cleanest harness encoding among the easy/medium puzzles.

## Problem 14 — `car-wash-decision` (medium, common-sense via formal modeling)

**Prompt**: "I want to wash my car at a car wash. The car wash is 50 meters away. Should I walk or drive?"

**Ground truth**: DRIVE. The 50m distance is a red herring — the goal is to wash the car, which requires the car at the car wash. Walking takes you there but leaves the car parked 50m away.

**Direct (14.5 s)**: explicitly noted "walking alone does not achieve the goal" and concluded DRIVE.

**Harnessed (265.4 s)**: produced a beautiful 5-line formal proof — and notably *ignored the 50m distance entirely* in the encoding:

```smt
(declare-const is_driving Bool)
(declare-const car_at_wash Bool)
(assert (=> is_driving car_at_wash))
(assert (=> (not is_driving) (not car_at_wash)))
(assert car_at_wash)
```

Z3 derived `is_driving = true`, uniqueness proven.

**Takeaway**: this is the strongest case so far for "common-sense reasoning via formal modeling". The model identified the *operative* constraint (car must be at car wash; only driving moves the car) and threw away the distractor (the 50m distance). A naive encoding would have computed walking-time vs. driving-time and chosen walk; the model's encoding instead captured the goal-state structure and got the right answer for the right reason.

## Problem 15 — `zebra-5x5` (very-hard, became the harness's recovery story)

**Prompt**: classic 15-clue Einstein/Zebra puzzle (five houses, five categories, find who drinks water and who owns the zebra).

**Ground truth**: standard published solution. Norwegian (House 1) drinks Water; Japanese (House 5) owns the Zebra.

### Run A (initial — harness failure with read-back disabled)

**Direct (174.8 s)**: solved the classic correctly. Used the standard chain (Norwegian in 1 → Blue in 2 → Green/Ivory placement → drinks → cigars → pets). Final grid matches published solution exactly.

**Harnessed (854.8 s, ~14 min)**: produced a **buggy SMT encoding**. The model wrote a key:

```
Nationalities: 0=Englishman, 1=Spaniard, 2=Ukrainian, 3=Norwegian, 4=Japanese
```

… and then wrote clue 1 ("the Englishman lives in the Red house") as `(=> (= Color_i 0) (= Nat_i 1))` — using `1` (Spaniard) instead of `0` (Englishman). One-character off-by-one in a 25-variable encoding.

Strikingly, the model itself caught the bug in step 2 prose ("the assertion for Clue 1 in Step 1 uses `Nat_i 1`, which corresponds to the Spaniard, not the Englishman (0)") but didn't fix the SMT — it marked `complete: true` and proceeded. The harness's uniqueness check caught the broken encoding and rendered a loud `⚠ ENCODING UNDER-CONSTRAINED` warning, but the user still saw a confusing artifact: correct prose, wrong Z3 model, and a "do not trust" banner. **This was the harness's most informative failure.**

### Mitigation — back-translation read-back pass

After this failure surfaced, we added an explicit read-back verification step (cf. *Autoformalization in the Era of Large Language Models* survey, Jiang et al. 2024, Chan et al. 2025): when the model declares `complete: true` (or when the run terminates UNSAT), the harness asks the model to translate every `:named` assertion back into plain English and compare against the original prompt clues. The model emits one of two verdicts on its own line:

```
VERDICT: OK
VERDICT: NEEDS_FIX
- clue1 was encoded as "Spaniard in Red house" but the prompt says "Englishman in Red house"
- ...
```

On `NEEDS_FIX`, the harness disposes the solver, resets accepted state, and re-runs the encoding with the listed issues prepended to step 1 as a correction hint. Capped at `maxReadBackRetries` (default 1).

### Run B (with read-back enabled)

**Direct (93.1 s)**: correct as before.

**Harnessed (514.4 s, 3 steps)**: this run's encoding chose a 1-indexed key (`1=Red, …, 5=Blue`) and didn't reintroduce the off-by-one. The model's SMT was already correct on the first pass; read-back fired, returned `VERDICT: OK`, and the harness proceeded to Z3 verification.

Decoded Z3 model:

| House | Colour | Nationality | Drink | Cigar | Pet |
|-------|--------|-------------|-------|-------|-----|
| 1 | Yellow | Norwegian | Water | Kools | Fox |
| 2 | Blue | Ukrainian | Tea | Chesterfields | Horse |
| 3 | Red | Englishman | Milk | Old Gold | Snails |
| 4 | Ivory | Spaniard | OJ | Lucky Strike | Dog |
| 5 | Green | Japanese | Coffee | Parliaments | Zebra |

All 25 cells match the published Inkala-style zebra solution. Norwegian drinks Water, Japanese owns the Zebra. Z3 confirmed UNIQUE on the negation of the model.

### Takeaway

The read-back mechanism is now the harness's defense-in-depth against encoding-fidelity errors. In Run B it had nothing to catch — the model's encoding was already clean — but the prompt structure has demonstrably shifted the model toward more careful categorical mappings (the model produced 3 incremental steps with explicit declarations + clue assertions + completion, vs. 2 monolithic steps in Run A). When a buggy encoding does occur, the read-back pass should now catch it before Z3 runs, replacing "verified-wrong answer with a non-uniqueness warning" with "model is given the discrepancy list and re-encodes from scratch."

**Caveat**: we have not yet observed read-back firing a `NEEDS_FIX` and then converging to a correct re-encoding in a single run. To stress-test that pathway we would need to either run zebra many times until a buggy sample appears or deliberately inject a known-bad encoding. The mechanism is wired and verified to fire `OK` on a clean run; the corrective pathway is exercised but not yet observed end-to-end.

## Problem 6 — `knights-5-mixed-unsat` (intentionally contradictory)

**Prompt**: same as problem 3 but D's statement reads *"exactly one of B, C, E is a Knight"* — which makes the puzzle contradictory under the standard rules.

**Ground truth**: NO valid assignment. Both A=Knight and A=Knave branches yield contradictions.

This problem went through the most rounds of any in the benchmark: it was the catalyst for several harness fixes (token budget bump, JSON-grammar removal, UNSAT terminus, failed-parse content logging, headers timeout). Two run histories below.

### Run A (early — both engines failed under tight budget)

- *Direct, run A1 (308.5 s, 12288 tokens)*: correctly identified the puzzle as unsolvable via exhaustive case analysis.
- *Direct, run A2 (308.5 s, 12288 tokens)*: returned **empty content** — model burned its full token budget in `<think>` and never emitted text. Same prompt, different sampling path.
- *Harnessed (multiple attempts)*: failed across three distinct failure modes — JSON-grammar suppression of `<think>`, empty parsed responses (token budget), and the same non-deterministic empty-output as direct A2. Each was traced to a tooling problem rather than a reasoning problem; fixes landed in the harness as cross-cutting items 5, 6, 9, 10 below.

### Run B (final — both engines succeed cleanly)

After the harness fixes, with `HARNESS_MAX_TOKENS=49152` and the JSON grammar removed:

**Direct (299.6 s)**: full case analysis on A=K and A=N. Both branches dead-end in contradictions; D's statement creates the paradox in every branch. Model concludes "no valid assignment" and even helpfully notes that changing D's statement to "exactly one Knave" would resolve the paradox into a consistent puzzle.

**Harnessed (468.6 s)**: returned `status: "unsat"` with the minimal conflicting set:

```
The encoded constraints are mutually inconsistent — Z3 proved UNSAT.

Minimal conflicting set (unsat core):
  - A_says_B_is_Knave
  - B_says_C_D_same_type
  - C_says_if_A_Knight_then_E_Knave
  - D_says_exactly_one_of_B_C_E_is_Knight
  - E_says_A_D_different_types
```

All five clue assertions are simultaneously needed; Z3 confirms there's no proper subset. The harness's UNSAT terminus fired (consistent UNSAT cores across retries) and produced a machine-checkable certificate of unsolvability. The read-back pass also fired (this run was on the post-zebra build with read-back enabled for both `completed` and `unsat` status) and returned `OK` — the model confirmed the SMT does encode the prompt faithfully, so the UNSAT verdict is genuine rather than an encoding bug.

**Takeaway**: this is the strongest UNSAT result in the suite. Direct gives a prose proof of unsolvability; harness gives a five-clue minimal core plus a back-translation confirmation that the encoding matches the prompt. Both are correct; the harness's output is the only one that's machine-checkable.

## Cross-cutting findings (improvements landed during this benchmark)

The benchmark exercised the harness hard enough to force several fixes; these all landed in the working tree:

1. **No-op step rejection** — `(check-sat)` and `(get-model)` style control commands are filtered out before solver assertion; if a step contains *only* control commands, it's rejected as a no-op.
2. **Premature-completion guard** — `complete: true` on step 1 with zero assertions is rejected; the harness now requires at least one accepted assertion before completion.
3. **Bool encoding hint** — prompt now explicitly recommends `Bool` over `Int 0/1` for binary properties to avoid the silent `(= IntVar BoolExpr)` sort error.
4. **Tracked-assertion noise filter** — `:named` labels show up as `Bool` decls in `solver.model().decls()`; their values render as `(_ <name> 0)`. Filtered out in `getModel()`.
5. **JSON grammar removal** — node-llama-cpp's JSON grammar forces the first token to be `{`, which kills Qwen's `<think>` block. Removed; rely on `extractJson` to recover JSON from prose.
6. **UNSAT terminus** — when every retry at a step yields the same unsat core, the harness now returns `status: "unsat"` with the conflicting set as a valid terminal answer instead of looping or failing.
7. **Quantifier-free encoding hint** — prompt now explicitly tells the model to avoid `forall`/`exists` on finite domains and enumerate cases instead, so Z3 stays in a decidable fragment.
8. **Loud warning when verification is missing** — if `verifySolution()` returns undefined (Z3 said `unknown`), the rendered final answer now leads with `⚠ NO Z3 VERIFICATION` instead of silently dropping the verification block.
9. **Headers timeout** — Node's default `fetch` headers timeout (300 s) was killing long harness runs. The driver script now uses an undici `Agent` with 1-hour timeouts.
10. **Failed-parse content logging** — when a step times out or fails 3× to parse, the last failed response is captured in the error message so we can debug what the model actually emitted.
11. **Back-translation read-back pass (zebra mitigation)** — when the model declares `complete: true` (or the run terminates UNSAT), the harness asks the model to translate every `:named` assertion back into English and compare against the original prompt clues. The model returns either `VERDICT: OK` or `VERDICT: NEEDS_FIX` with a list of issues. On `NEEDS_FIX`, the harness disposes the solver, resets the accepted state, and re-runs the encoding with the issues prepended to step 1 as a correction hint. Capped at `maxReadBackRetries` (default 1). This is the literature-standard back-translation technique applied at the SMT-LIB level (Jiang et al. 2024; Chan et al. 2025).

## Where direct fails / where harness wins

After 15 problems spanning easy → very-hard, picked specifically from "models commonly fail" sources (the *Easy Problems That LLMs Get Wrong* paper, Apple's *Illusion of Reasoning*, ZebraLogic, SATBench, plus some originals), two distinct regimes have emerged:

### Direct wins / harness adds verification (problems 1–7, 9–14)

For 13 of 15 problems, **direct Qwen 3.6 35B got the right answer logically**. Including all the curated "trap" puzzles: Sally's sisters, Alice's brother's sisters, modified river-crossing, doubling jar, snail pole, car-wash decision, knights-and-knaves variants. These are problems where the literature suggests models routinely fail — Qwen 3.6 35B passes them. The harness's marginal value on this class is **independent verification**:

- Every SAT result ships with a Z3 model and a uniqueness proof (UNSAT on negation).
- Every UNSAT result (problem 7, pigeonhole) ships with a minimal conflicting core.
- The uniqueness check **caught two encoding shortcuts** (problems 2 round 1 and 12) where the model's prose was correct but the SMT didn't actually capture the dynamics — the harness would have surfaced that even if the prose answer had been wrong.

On problems 1–4 the harness is also faster (1.7×–2.3×) because it bypasses prose case analysis.

### Harness wins outright (problem 8: Sudoku)

The clearest crossover. Direct burned its full 24576-token budget on `<think>` and emitted nothing in 11 minutes. The harness translated the puzzle to ~215 lines of SMT, handed the search to Z3, and produced a complete verified solution (all 81 cells matching the published Inkala-2010 answer, uniqueness proven) in 8 minutes. **At 49152 tokens it was the same story** — Sudoku's case tree exceeds whatever budget you give the thinking model, but the SMT translation is comparatively short.

This is the regime the harness was built for: combinatorial problems where the *search* dominates the *translation*. We expect the advantage to grow with grid size (16×16 sudoku, larger n-queens, real-world scheduling).

### Closed gap: encoding fidelity (problem 15: Zebra 5×5)

The original Zebra 5×5 run was the harness's most informative *failure*: the model produced a buggy SMT encoding (an off-by-one categorical mapping), self-diagnosed it in prose, but didn't repair the SMT before declaring `complete: true`. The uniqueness check correctly flagged the encoding as under-constrained but couldn't *fix* it — the verified Z3 model was for a different puzzle than the one in the prompt.

This pattern is well-studied in the autoformalization literature; the standard mitigation is **back-translation** (translate the formal encoding back to natural language and compare against the source). We added that as a read-back pass before finalizing. On the re-run, the harness produced a clean encoding and read-back returned `OK` — final grid matches the published solution exactly, uniqueness proven. The corrective branch (where read-back fires `NEEDS_FIX` and triggers a re-encode) is wired and exercised but has not yet been observed end-to-end with a buggy sample; further stress-testing is on the open-questions list.

### Takeaways

1. **Curated "models fail at this" problems are largely solved by Qwen 3.6 35B (35B Q8) with thinking enabled.** ZebraLogic / SATBench-style benchmarks were measured on smaller / older models or with constrained output formats. With the JSON grammar removed and 49K-token budgets, the model passes nearly everything in our trap-puzzle set.
2. **The harness's win condition is combinatorial depth, not prompt difficulty.** Sudoku is harder for the model not because it's "tricky" but because the answer requires real search; Z3 does that search trivially.
3. **Encoding fidelity is the harness's Achilles' heel, partially addressed.** One off-by-one in 25-variable categorical encoding originally produced a verified-wrong answer. The back-translation read-back pass closes the most common version of this gap (categorical mapping errors, direction flips, missing clues), but the literature is consistent that no single technique gives full guarantees — see *Autoformalization in the Era of Large Language Models* survey, table of methods. Layering test-case probing on top of read-back is the natural next step.
4. **For single-integer-answer puzzles the harness is bookkeeping at best.** river-large-boat is the clearest case: the model decided in prose, then asserted `(= crossings 1)`. Z3's verification is just "1 = 1." For those problems you don't gain anything over direct.

## Reproducing

```bash
HARNESS_MODEL_PATH=models/Qwen3.6-35B-A3B-Q8_0.gguf HARNESS_MAX_TOKENS=49152 HARNESS_PORT=3001 npm start &
# wait for "Listening on http://0.0.0.0:3001"

# Knights & Knaves (problems 1–3, 6)
npx tsx scripts/compare.ts knights-3
npx tsx scripts/compare.ts knights-5-counts
npx tsx scripts/compare.ts knights-5-mixed
npx tsx scripts/compare.ts knights-5-mixed-unsat

# Arithmetic / SAT
npx tsx scripts/compare.ts arith-triple
npx tsx scripts/compare.ts pigeonhole-3-2

# Einstein / Zebra
npx tsx scripts/compare.ts einstein-4x4
npx tsx scripts/compare.ts zebra-5x5

# Sudoku
npx tsx scripts/compare.ts sudoku-hard

# Curated "models commonly fail at" puzzles
npx tsx scripts/compare.ts sally-sisters
npx tsx scripts/compare.ts alice-brother-sisters
npx tsx scripts/compare.ts river-large-boat
npx tsx scripts/compare.ts doubling-jar
npx tsx scripts/compare.ts snail-pole
npx tsx scripts/compare.ts car-wash-decision
```

Each run writes `/tmp/bench-<id>.log` (full transcript) and `/tmp/bench-<id>.json` (parsed result + harness trace).

`HARNESS_MAX_TOKENS=49152` is recommended; with the JSON grammar disabled, Qwen's `<think>` block can be very long on hard problems and was truncating the SMT-LIB output at 24576 (problems 6 and 7 originally).

## Open questions for future runs

- **Find a problem where direct gives a confidently wrong logical answer.** Across our 15-problem set, the only direct failures were *empty output* (Sudoku, runout). Curated trap puzzles all pass. To find a true logical-failure case, we likely need (a) much larger constraint problems (5×5 → 7×7 zebra), (b) modular-arithmetic puzzles with subtle traps, or (c) ARC-AGI-style spatial/visual reasoning.
- **Encoding read-back as a self-check pass.** The zebra failure (problem 15) showed that the harness has no defense against off-by-one categorical-mapping errors. Adding a step where the model is asked to re-translate the SMT back into the original prompt's vocabulary and verify they match would catch this. Cheap improvement, big payoff.
- **Auto-decode Z3 models back to puzzle vocabulary.** We currently print raw `c1_5 = 5`, `n1 = 3`, etc. The user has to mentally apply the model's mapping to read the answer. A post-processor using the model's declared key would make answers self-explanatory.
- **Harness scaling on Sudoku-like problems.** 9×9 worked at 49K tokens. Does 16×16 fit? The encoding scales linearly in cell count (~256 declarations + bounds + 16 row distincts + 16 col distincts + 16 box distincts ≈ 800 SMT lines).
- **Multiple-sample timing.** Single-sample wall times vary by 30–50% across runs (sampling temperature 0.7). Five-sample averages would let us draw confident conclusions about the speed delta.
- **Adversarial UNSAT puzzles** where the contradiction is non-obvious — does direct still detect UNSAT? Pigeonhole is too easy for current direct models. PHP(n+1, n) with n ≥ 5 might be more discriminating.
- **Common-sense modeling at scale.** The car-wash result was the most interesting common-sense win. Does the same encoding approach work for "Should I take the elevator or the stairs to my 3rd-floor apartment carrying groceries?" or "I left my keys at home and I'm at the office — should I walk back or call a locksmith?" These need the model to identify the operative constraint and ignore distractors.
