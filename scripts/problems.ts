/**
 * Benchmark problem set, ordered roughly by difficulty for a 30B-class
 * local model. Each entry carries the verbatim prompt sent to both runs.
 */

export interface Problem {
  id: string;
  type: string;
  difficulty: "easy" | "medium" | "hard" | "very-hard";
  prompt: string;
  expectedAnswer: string;
  /** Soft cap on harness iterations. */
  maxSteps?: number;
}

export const PROBLEMS: Record<string, Problem> = {
  "knights-3": {
    id: "knights-3",
    type: "Knights & Knaves (3 people, direct statements)",
    difficulty: "easy",
    prompt: `On a logic island, every inhabitant is either a Knight (always tells the truth) or a Knave (always lies). You meet three inhabitants: A, B, and C.

A says: "B is a Knight."
B says: "A and C are of different types."
C says: "A is a Knave."

Determine the type (Knight or Knave) of each of A, B, and C. Show your reasoning step by step, then state the final assignment.`,
    expectedAnswer: "A=Knight, B=Knight, C=Knave",
  },

  "knights-5-counts": {
    id: "knights-5-counts",
    type: "Knights & Knaves (5 people, count-based statements)",
    difficulty: "medium",
    prompt: `On a logic island, every inhabitant is either a Knight (always tells the truth) or a Knave (always lies). You meet five inhabitants: A, B, C, D, and E. Each makes one statement:

A says: "Exactly one of us five is a Knave."
B says: "Exactly two of us five are Knaves."
C says: "Exactly three of us five are Knaves."
D says: "Exactly four of us five are Knaves."
E says: "All five of us are Knaves."

Determine the type (Knight or Knave) of each. Show your reasoning, then state the final assignment.`,
    expectedAnswer: "A=Knave, B=Knave, C=Knave, D=Knight, E=Knave (4 Knaves total)",
    maxSteps: 14,
  },

  "knights-5-mixed": {
    id: "knights-5-mixed",
    type: "Knights & Knaves (5 people, mixed self/other-referential, unique solution)",
    difficulty: "hard",
    prompt: `On a logic island, every inhabitant is either a Knight (always tells the truth) or a Knave (always lies). You meet five inhabitants: A, B, C, D, and E.

A says: "B is a Knave."
B says: "C and D are the same type."
C says: "If A is a Knight, then E is a Knave."
D says: "Among B, C, and E, exactly three are Knights."
E says: "A and D are of different types."

Determine the type (Knight or Knave) of each. Show your reasoning, then state the final assignment.`,
    expectedAnswer: "A=Knave, B=Knight, C=Knight, D=Knight, E=Knight (verified by case analysis: A=T leads to C=D and C≠D contradiction; A=F gives unique consistent assignment)",
    maxSteps: 16,
  },

  "knights-5-mixed-unsat": {
    id: "knights-5-mixed-unsat",
    type: "Knights & Knaves (5 people, mixed — INTENTIONALLY UNSAT)",
    difficulty: "hard",
    prompt: `On a logic island, every inhabitant is either a Knight (always tells the truth) or a Knave (always lies). You meet five inhabitants: A, B, C, D, and E.

A says: "B is a Knave."
B says: "C and D are the same type."
C says: "If A is a Knight, then E is a Knave."
D says: "Among B, C, and E, exactly one is a Knight."
E says: "A and D are of different types."

Determine the type (Knight or Knave) of each. Show your reasoning, then state the final assignment.`,
    expectedAnswer: "NO SOLUTION — both A=T and A=F branches yield contradictions. Tests UNSAT detection.",
    maxSteps: 16,
  },

  "arith-triple": {
    id: "arith-triple",
    type: "Diophantine constraint (sum + product, ordered triple)",
    difficulty: "hard",
    prompt: `Find three positive integers a, b, c satisfying ALL of the following:
1. a + b + c = 30
2. a × b × c = 360
3. a < b < c
4. Each of a, b, c is at most 25.

Show your reasoning step by step. State the unique triple (a, b, c).`,
    expectedAnswer: "a=2, b=10, c=18 (sum=30, product=360, ordered)",
    maxSteps: 12,
  },

  "zebra-5x5": {
    id: "zebra-5x5",
    type: "Einstein/Zebra puzzle (5 houses × 5 categories, 15 clues)",
    difficulty: "very-hard",
    prompt: `There are five houses in a row, numbered 1 (leftmost) through 5 (rightmost). Each house has a unique colour, a unique nationality occupant, a unique drink, a unique cigar brand, and a unique pet. The categories and their five values:

Colours: Red, Green, Ivory, Yellow, Blue
Nationalities: Englishman, Spaniard, Ukrainian, Norwegian, Japanese
Drinks: Coffee, Tea, Milk, Orange juice, Water
Cigars: Old Gold, Kools, Chesterfields, Lucky Strike, Parliaments
Pets: Dog, Snails, Fox, Horse, Zebra

Clues:
1. The Englishman lives in the Red house.
2. The Spaniard owns the Dog.
3. Coffee is drunk in the Green house.
4. The Ukrainian drinks Tea.
5. The Green house is immediately to the right of the Ivory house.
6. The Old Gold smoker owns Snails.
7. Kools are smoked in the Yellow house.
8. Milk is drunk in the middle house (house 3).
9. The Norwegian lives in the first house (house 1).
10. The man who smokes Chesterfields lives in the house next to the man with the Fox.
11. Kools are smoked in the house next to the house where the Horse is kept.
12. The Lucky Strike smoker drinks Orange juice.
13. The Japanese smokes Parliaments.
14. The Norwegian lives next to the Blue house.
15. (Standard zebra puzzle — no further clues.)

Determine, for each house 1..5, the colour, nationality, drink, cigar, and pet. State who drinks Water and who owns the Zebra. Show your reasoning, then state the full assignment.`,
    expectedAnswer:
      "Standard zebra puzzle answer. House 1: Yellow, Norwegian, Water, Kools, Fox. House 2: Blue, Ukrainian, Tea, Chesterfields, Horse. House 3: Red, Englishman, Milk, Old Gold, Snails. House 4: Ivory, Spaniard, Orange juice, Lucky Strike, Dog. House 5: Green, Japanese, Coffee, Parliaments, Zebra. Norwegian drinks water, Japanese owns the zebra.",
    maxSteps: 25,
  },

  "hanoi-4-d2-locked": {
    id: "hanoi-4-d2-locked",
    type: "Modified 4-disk Tower of Hanoi (D2 forbidden from peg B; non-standard solution)",
    difficulty: "very-hard",
    prompt: `Standard 4-disk Tower of Hanoi: disks D1 (smallest), D2, D3, and D4 (largest) start stacked on peg A in size order (D4 at the bottom, D1 on top). The goal is to move all four disks to peg C in the same order. There are three pegs: A, B, and C. Standard rules apply:
- Move one disk at a time.
- Only the top disk of any peg can be moved.
- A larger disk may never be placed on top of a smaller disk.

ADDITIONAL RESTRICTION: disk D2 (the second-smallest) may never rest on peg B at any point during the solution. (D2 may be on peg A or peg C only.)

Determine whether this puzzle can be solved under both the standard rules and the restriction. If yes, give the minimum number of moves and a valid move sequence. If no, prove the puzzle is impossible.

Show your reasoning step by step. State the answer as either a single integer (minimum moves) followed by the sequence, or "IMPOSSIBLE" with justification.`,
    expectedAnswer:
      "Solvable; direct LLMs in prior runs found 35 moves. Direct LLMs commonly pattern-match to the standard 15-move recursive solution and emit illegal moves where D2 visits B; the agent must use Prolog/CLP(FD) iterative deepening to find the genuine optimum.",
    maxSteps: 30,
  },

  "hanoi-d2-locked": {
    id: "hanoi-d2-locked",
    type: "Modified Tower of Hanoi (forbidden-peg restriction; non-standard solution)",
    difficulty: "hard",
    prompt: `Standard Tower of Hanoi puzzle: three disks D1 (smallest), D2 (medium), and D3 (largest) start stacked on peg A in size order (D3 at the bottom). The goal is to move all three to peg C in the same order. There are three pegs: A, B, and C. Standard rules apply:
- Move one disk at a time.
- Only the top disk of any peg can be moved.
- A larger disk may never be placed on top of a smaller disk.

ADDITIONAL RESTRICTION: disk D2 may never rest on peg B at any point during the solution. (D2 may be on peg A or peg C only.)

Determine whether this puzzle can be solved under both the standard rules and the restriction. If yes, give the minimum number of moves and a valid move sequence. If no, prove the puzzle is impossible with a clear argument.

Show your reasoning step by step, then state the answer as either a single integer (minimum moves) or "IMPOSSIBLE" with justification.`,
    expectedAnswer:
      "11 moves (solvable, but non-standard). The puzzle IS solvable, contrary to a tempting one-shot impossibility argument that assumes D3 must move A→C directly. Since D3 can go A→B then B→C, the puzzle has a solution. One optimal sequence: D1:A→B, D2:A→C, D1:B→C, D3:A→B, D1:C→B, D2:C→A, D1:B→A, D3:B→C, D1:A→B, D2:A→C, D1:B→C. D2's path is C→A→C — never B. Models commonly fail in two ways: (a) pattern-match to the standard 7-move solution and emit illegal moves (direct's typical failure); (b) accept the surface-level impossibility argument (D3 can never reach C) and miss that D3 can use peg B as an intermediate stop.",
    maxSteps: 12,
  },

  "bridge-torch": {
    id: "bridge-torch",
    type: "Bridge-and-torch optimisation (non-greedy minimum)",
    difficulty: "hard",
    prompt: `Four people need to cross a rickety bridge at night. They share one torch. The bridge can hold at most two people at a time. The torch must be carried for any crossing — so when two people cross together, they share the torch and travel at the slower person's pace. After people reach the far side, the torch has to be carried back so others can cross.

The four people take 1, 2, 6, and 10 minutes to cross individually.

What is the minimum total time for all four to be on the far side of the bridge? Show your reasoning, including the schedule of crossings, then state the answer as a single integer (minutes).`,
    expectedAnswer:
      "17. The non-greedy optimum sends the two slowest together: (1+2 cross: 2 min), (1 returns: 1 min), (6+10 cross: 10 min), (2 returns: 2 min), (1+2 cross: 2 min) = 17. The greedy 'fastest escorts everyone' strategy gives 1+1+6+1+10 = … wait the greedy total is actually 2+1+6+1+10 = 20. Models that pick the greedy approach commonly answer 20 (or 19 if they miscalculate); the non-greedy 17 requires recognising that it pays to send the two slowest TOGETHER and bring back a slower returnee.",
    maxSteps: 12,
  },

  "n-queens-8": {
    id: "n-queens-8",
    type: "8-queens (find a valid placement)",
    difficulty: "hard",
    prompt: `Place 8 queens on an 8×8 chessboard so that no two queens attack each other (no two in the same row, same column, or same diagonal).

Provide a valid placement as 8 (row, column) pairs with rows and columns numbered 1..8. Show your reasoning, then state the placement as a list, e.g. "(1, 4), (2, 7), …".`,
    expectedAnswer:
      "Any of the 92 valid 8-queens solutions. One example: (1,1), (2,5), (3,8), (4,6), (5,3), (6,7), (7,2), (8,4). Direct LLMs commonly emit a placement that LOOKS plausible but has two queens on a shared diagonal — the failure is silent unless verified.",
    maxSteps: 12,
  },

  "snail-pole": {
    id: "snail-pole",
    type: "Off-by-one trap (models often answer 12 instead of 9)",
    difficulty: "medium",
    prompt: `A snail is at the bottom of a 12-meter vertical pole. Every day, the snail climbs UP 4 meters during the day, but then slides DOWN 3 meters during the night while it sleeps. The snail keeps repeating this pattern.

Importantly: once the snail reaches or passes the top of the pole during the day, it stays at the top — it has "escaped" the pole and the night's slide does not apply.

How many days (counting the first day as day 1) does it take for the snail to reach the top of the pole?

Show your reasoning step by step, then state the answer as a single integer.`,
    expectedAnswer:
      "9. After day 8's slide, the snail is at 8m (net 1m/day × 8). On day 9 it climbs 4m and reaches 12m during the day, escaping. Naive arithmetic 12 / (4-3) = 12 ignores that the final day's climb doesn't get followed by a slide.",
    maxSteps: 10,
  },

  "doubling-jar": {
    id: "doubling-jar",
    type: "Exponential-growth trap (models often answer 30 instead of 59)",
    difficulty: "easy",
    prompt: `Bacteria in a jar double in number every minute. The jar starts with 1 bacterium at minute 0. After 60 minutes the jar is completely full.

At what minute is the jar exactly half full? Show your reasoning, then state the answer as a single integer.`,
    expectedAnswer:
      "59. Since the population doubles every minute, the jar at minute 59 is half of what it is at minute 60 (full). Models that naively compute 60/2 = 30 are confusing linear and exponential growth.",
    maxSteps: 8,
  },

  "river-large-boat": {
    id: "river-large-boat",
    type: "Modified river-crossing puzzle (pattern-matching trap)",
    difficulty: "medium",
    prompt: `A farmer needs to transport a wolf, a goat, and a cabbage across a wide river using a boat.

The boat is spacious: it can carry the farmer together with all three items (the wolf, the goat, AND the cabbage) at once in a single trip.

The standard predator-prey rules still hold: if left alone without the farmer, the wolf would eat the goat, and the goat would eat the cabbage. (When the farmer is present anywhere, those interactions are prevented.)

What is the minimum number of one-way boat crossings required to get the farmer and all three items to the far side of the river? Reason carefully, then state the answer as a single integer.`,
    expectedAnswer:
      "1. The boat fits the farmer + all three items in one trip; everyone crosses together; the predator-prey constraints are inactive because the farmer is always present. Models commonly pattern-match to the classic 7-trip solution and ignore the boat-capacity statement.",
    maxSteps: 10,
  },

  "alice-brother-sisters": {
    id: "alice-brother-sisters",
    type: "Family-counting puzzle (AIW variant)",
    difficulty: "easy",
    prompt: `Alice has 4 brothers and 1 sister. How many sisters does Alice's brother have?

Show your reasoning, then state the answer as a single integer.`,
    expectedAnswer:
      "2. Alice's brother is a boy whose sisters are the girls in the family. The family has Alice (girl) + Alice's 1 sister (girl) = 2 girls. So each brother has 2 sisters. Models commonly answer 1 (echoing 'Alice has 1 sister' without re-counting from the brother's perspective) or 0 (forgetting Alice).",
    maxSteps: 10,
  },

  "sally-sisters": {
    id: "sally-sisters",
    type: "Family-counting puzzle (known LLM failure mode)",
    difficulty: "easy",
    prompt: `Sally is a girl. Sally has 3 brothers. Each of Sally's brothers has 2 sisters. How many sisters does Sally have?

Show your reasoning step by step, then state the final answer as a single integer.`,
    expectedAnswer:
      "1. The family has 3 boys + Sally + Sally's other sisters. Each brother has 2 sisters, meaning the family has exactly 2 girls. Sally is one of the girls, so Sally has 1 sister. Models commonly answer 2 (confusing 'sisters per brother' with 'sisters Sally has').",
    maxSteps: 10,
  },

  "car-wash-decision": {
    id: "car-wash-decision",
    type: "Common-sense decision via constraint propagation",
    difficulty: "medium",
    prompt: `I want to wash my car at a car wash. The car wash is 50 meters away from where my car is currently parked. Should I walk to the car wash, or drive my car to the car wash?

Identify the goal, the relevant facts, and the constraints. Reason carefully about whether each option achieves the goal. Give a definitive answer: WALK or DRIVE.`,
    expectedAnswer:
      "DRIVE. The goal is to wash the car, which requires the car to be at the car wash. Walking to the car wash leaves the car parked 50m away — the car wash cannot wash a car that isn't there. The 50m distance is a red herring; what matters is that only the 'drive' option transports the car to its required location.",
    maxSteps: 10,
  },

  "sudoku-hard": {
    id: "sudoku-hard",
    type: "Sudoku (9×9, hard, uniquely solvable)",
    difficulty: "very-hard",
    prompt: `Solve the following 9x9 Sudoku puzzle. Each row, each column, and each of the nine 3x3 boxes must contain the digits 1..9 exactly once. A '.' marks an empty cell.

Row 1: 8 . . . . . . . .
Row 2: . . 3 6 . . . . .
Row 3: . 7 . . 9 . 2 . .
Row 4: . 5 . . . 7 . . .
Row 5: . . . . 4 5 7 . .
Row 6: . . . 1 . . . 3 .
Row 7: . . 1 . . . . 6 8
Row 8: . . 8 5 . . . 1 .
Row 9: . 9 . . . . 4 . .

Show your reasoning, then state the full 9x9 solution grid (one row per line, digits separated by spaces).`,
    expectedAnswer: `Row 1: 8 1 2 7 5 3 6 4 9
Row 2: 9 4 3 6 8 2 1 7 5
Row 3: 6 7 5 4 9 1 2 8 3
Row 4: 1 5 4 2 3 7 8 9 6
Row 5: 3 6 9 8 4 5 7 2 1
Row 6: 2 8 7 1 6 9 5 3 4
Row 7: 5 2 1 9 7 4 3 6 8
Row 8: 4 3 8 5 2 6 9 1 7
Row 9: 7 9 6 3 1 8 4 5 2`,
    maxSteps: 20,
  },

  "pigeonhole-3-2": {
    id: "pigeonhole-3-2",
    type: "Pigeonhole CNF (3 pigeons, 2 holes — UNSAT)",
    difficulty: "medium",
    prompt: `Determine whether the following CNF formula is satisfiable. Variables are p_ij for i in {1,2,3} and j in {1,2}, meaning "pigeon i is in hole j". Find a satisfying assignment if one exists, or prove no assignment satisfies all clauses.

Clauses:
1.  (p_11 ∨ p_12)
2.  (p_21 ∨ p_22)
3.  (p_31 ∨ p_32)
4.  (¬p_11 ∨ ¬p_21)
5.  (¬p_11 ∨ ¬p_31)
6.  (¬p_21 ∨ ¬p_31)
7.  (¬p_12 ∨ ¬p_22)
8.  (¬p_12 ∨ ¬p_32)
9.  (¬p_22 ∨ ¬p_32)

The first three clauses say each pigeon is in at least one hole. Clauses 4-6 say no two pigeons share hole 1; clauses 7-9 say no two share hole 2.

State whether the formula is SAT or UNSAT, with reasoning.`,
    expectedAnswer:
      "UNSAT (pigeonhole principle: 3 pigeons cannot fit into 2 holes without sharing). The minimal conflict involves all 9 clauses simultaneously.",
    maxSteps: 14,
  },

  "math-amgm-2": {
    id: "math-amgm-2",
    type: "Math theorem (AM-GM for two non-negative reals)",
    difficulty: "medium",
    prompt: `Prove the AM-GM inequality for two non-negative real numbers: for all real x, y ≥ 0, the geometric mean does not exceed the arithmetic mean. Formally:

  ∀ x y : ℝ, 0 ≤ x → 0 ≤ y → 2 * sqrt (x * y) ≤ x + y

Equivalently (squaring both sides under non-negativity), 4·x·y ≤ (x + y)². You may prove either form.

Use the verify_lean tool: write the proof in Lean 4 with Mathlib. Useful tactics: \`nlinarith\`, \`polyrith\`, \`Real.sqrt_le_sqrt\`, \`Real.sq_sqrt\`, \`Real.sqrt_mul_self\`. The simplest proof of the squared form takes one line with nlinarith and the fact (x - y)² ≥ 0.`,
    expectedAnswer:
      "Proved via nlinarith using (x - y)² ≥ 0 (equivalently 4xy ≤ (x+y)²).",
    maxSteps: 8,
  },

  "ramsey-3-3": {
    id: "ramsey-3-3",
    type: "Math theorem (Ramsey number R(3,3) = 6)",
    difficulty: "hard",
    prompt: `Prove that the Ramsey number R(3,3) = 6.

**Definition.** R(s,t) is the smallest natural number N such that every 2-coloring of the edges of the complete graph K_N (each edge colored red or blue) contains either a red K_s (red clique on s vertices) or a blue K_t (blue clique on t vertices). For R(3,3): the smallest N where every 2-edge-coloring of K_N has a monochromatic triangle.

**What you must prove.** R(3,3) = 6 has two halves; both are required:

  (a) **Upper bound, R(3,3) ≤ 6.** Every 2-edge-coloring of K_6 contains a monochromatic K_3.

  (b) **Lower bound, R(3,3) > 5** (equivalently, R(3,3) ≥ 6). There EXISTS a 2-edge-coloring of K_5 with no monochromatic K_3.

The combined statement is R(3,3) = 6.

**Suggested approaches.**

For (a) — a textbook pigeon-hole argument:
  1. Pick any vertex v of K_6. Its 5 incident edges are 2-colored, so by pigeonhole at least 3 are the same color, say red. Let those neighbors be a, b, c.
  2. If any edge among {a, b, c} is red, that edge plus v forms a red triangle. If all three of {ab, ac, bc} are blue, they form a blue triangle. Either way: a monochromatic triangle.
  This pigeon-hole step is short enough to do in Lean, but you can also encode "every 2-edge-coloring of K_6 has a monochromatic K_3" as a SAT query in Z3 (assert the negation; Z3 returns UNSAT). Z3 will solve it in milliseconds.

For (b) — exhibit the *pentagon* coloring of K_5: arrange the 5 vertices as a regular 5-cycle; color the 5 cycle edges red and the 5 chord (diagonal) edges blue. No three vertices form a monochromatic K_3 because:
  - Three vertices forming a red triangle would require three pairwise-cycle-adjacent vertices, but the 5-cycle has no triangle.
  - Three vertices forming a blue triangle would require three pairwise-non-adjacent vertices on the cycle, but C_5 has independence number 2.
  This is verifiable by Z3 in milliseconds (assert the coloring and check there's no monochromatic K_3) or formalizable in Lean.

**Tools you have.**
  - \`lean_search\` and \`verify_lean\` / \`proof_start\` for formal Lean+Mathlib proofs. Mathlib has \`SimpleGraph\`, \`Finset\`, and clique predicates; \`SimpleGraph.IsNClique\` is the basic lemma. Search for "Ramsey", "monochromatic", "IsNClique" to find the relevant infrastructure.
  - \`verify_smt\` for Z3-based SAT/UNSAT verification of small finite cases (K_5 and K_6 are tiny — 10 and 15 edges respectively, all triangle constraints fit comfortably).
  - The Prolog engines aren't a great fit for this finite-clique problem; stick to Lean + Z3.

**Output expectations.** A correct proof of R(3,3) = 6 needs both halves. The Lean proof (or Z3 verification) of each half is what counts as the answer; the natural-language prose is supporting commentary. State the final answer as: "R(3,3) = 6, established by [your method for upper bound] and [your method for lower bound]." Include the Lean proofs / SMT-LIB encodings in the response — they'll be auto-appended via the harness's verified-proof channel when you call \`done\`.`,
    expectedAnswer:
      "R(3,3) = 6. Upper bound R(3,3) ≤ 6 by pigeon-hole on a vertex's 5 neighbours (Lean or Z3 UNSAT on K_6 with no monochromatic triangle). Lower bound R(3,3) ≥ 6 by exhibiting the pentagon coloring of K_5 (cycle edges red, chords blue — no monochromatic triangle).",
    maxSteps: 80,
  },

  "math-sqrt-2-irrational": {
    id: "math-sqrt-2-irrational",
    type: "Math theorem (proof by contradiction: √2 is irrational)",
    difficulty: "medium",
    prompt: `Prove that √2 is irrational in Lean 4 + Mathlib. Formally:

  Irrational (Real.sqrt 2)

This is a classic proof-by-contradiction theorem. Mathlib has it stated under a canonical name; \`lean_search\` should find it. You can either:
1. Cite the canonical Mathlib lemma directly via verify_lean / proof_step's \`exact\`, or
2. Prove it from scratch (a real challenge — recommend option 1).

Use \`proof_start\` + \`proof_step\` to walk through the proof step by step if you choose to do it from scratch, or use \`verify_lean\` for a one-shot proof if you can recall the lemma name.`,
    expectedAnswer:
      "Proved by citing Mathlib's `irrational_sqrt_two` (or equivalent) — the canonical lemma name in Mathlib.NumberTheory.Irrational.",
    maxSteps: 12,
  },

  "math-gauss-sum": {
    id: "math-gauss-sum",
    type: "Math theorem (induction with Finset.sum: Gauss formula)",
    difficulty: "hard",
    prompt: `Prove Gauss's formula in Lean 4 + Mathlib: the sum of integers from 0 to n equals n·(n+1)/2. Formally, with Finset notation:

  ∀ n : ℕ, 2 * (∑ i ∈ Finset.range (n + 1), i) = n * (n + 1)

(We multiply both sides by 2 to avoid division. The equivalent statement Mathlib likely has is \`Finset.sum_range_id\` or \`Gauss_sum\`-named.)

This is a stepwise inductive proof with Finset / sum manipulation. Use \`proof_start\` and \`proof_step\`. Suggested skeleton:
1. \`intro n\`
2. \`induction n with | zero => ?_ | succ k ih => ?_\`
3. Base case: \`norm_num\` or \`simp\`
4. Inductive step: rewrite \`Finset.range (k + 1 + 1) = insert (k+1) (Finset.range (k+1))\` then unfold the sum.
5. \`Finset.sum_insert\`, \`omega\`, \`ring\` are useful tactics.

\`lean_search\` is your friend — try queries like "Finset sum range" or "sum_range_id".`,
    expectedAnswer:
      "Proved via induction; base case norm_num/decide; inductive step uses Finset.sum_range_succ + ring/omega.",
    maxSteps: 25,
  },

  "math-induction-pow2-gt-n": {
    id: "math-induction-pow2-gt-n",
    type: "Math theorem (induction: 2^n > n for all n : ℕ)",
    difficulty: "medium",
    prompt: `Prove that 2^n > n for every natural number n. Formally:

  ∀ n : ℕ, n < 2 ^ n

This is a stepwise inductive proof. Use \`proof_start\` to open a session, then apply tactics one at a time via \`proof_step\` so the goal state evolves visibly. You'll see the goal after every tactic — pick the next move based on what's left.

Some hints (use them as you see fit):
- Induction on n: base case n = 0 reduces to 0 < 1; inductive step needs n+1 < 2^(n+1) given ih : n < 2^n.
- Useful tactics: \`intro n\`, \`induction n with | zero => ?_ | succ k ih => ?_\`, \`norm_num\`, \`omega\`, \`linarith\`, \`simp [pow_succ]\`, \`show <expr>\`, \`have h := ...\`.
- \`pow_succ\` rewrites 2^(k+1) = 2^k * 2.
- If you go down a wrong path, \`proof_undo\` rolls back tactics without restarting.

Proof technique is your call. Start with \`proof_start\` and work step by step.`,
    expectedAnswer:
      "Proved by induction on n: base case n = 0 gives 0 < 1 (trivial); inductive step uses ih and pow_succ to derive n+1 < 2^(n+1).",
    maxSteps: 30,
  },

  "math-induction-square-plus-self-even": {
    id: "math-induction-square-plus-self-even",
    type: "Math theorem (induction: n² + n is even for all n : ℕ)",
    difficulty: "medium",
    prompt: `Prove by induction in Lean 4 + Mathlib that for every natural number n, n² + n is even. Formally:

  ∀ n : ℕ, 2 ∣ n^2 + n

This is a stepwise proof — use \`proof_start\` to open a session, then apply tactics one at a time via \`proof_step\` so the goal state evolves visibly. Pick any structure you like (induction, even/odd case split, etc.); the harness lets you see the goal after every tactic. When all goals close, call \`proof_close\`.

Hints if you want them:
- Induction on n is the most natural route.
- Base case: \`norm_num\` or \`decide\` should handle 0² + 0.
- Inductive step: from \`2 ∣ k^2 + k\`, derive \`2 ∣ (k+1)^2 + (k+1)\`. Note (k+1)² + (k+1) = k² + 3k + 2 = (k² + k) + 2(k + 1).
- \`omega\` and \`ring_nf\` are useful tactics. \`Nat.dvd_add\` chains divisibilities.`,
    expectedAnswer:
      "Proved via induction on n; base case trivial, inductive step uses (k+1)² + (k+1) = (k² + k) + 2(k+1) and Nat.dvd_add.",
    maxSteps: 25,
  },

  "math-infinitely-many-primes": {
    id: "math-infinitely-many-primes",
    type: "Math theorem (Euclid: infinitely many primes)",
    difficulty: "hard",
    prompt: `Prove Euclid's theorem in Lean 4 + Mathlib: for every natural number n, there exists a prime p with p > n. Formally:

  ∀ n : ℕ, ∃ p, n < p ∧ Nat.Prime p

This is a classic theorem; Mathlib has it. Use \`lean_search\` to find the canonical lemma name (it lives in \`Mathlib.NumberTheory\` somewhere). Then either cite it directly or write a short proof using \`Nat.exists_infinite_primes\`-style results. You may also try \`exact?\` or \`apply?\` inside the proof to let Lean suggest a closing tactic.`,
    expectedAnswer:
      "Proved via Mathlib's existing infinitude-of-primes lemma (Nat.exists_infinite_primes or similar).",
    maxSteps: 12,
  },

  "math-sum-evens": {
    id: "math-sum-evens",
    type: "Math theorem (sum of two evens is even)",
    difficulty: "easy",
    prompt: `Prove that the sum of two even integers is even. Formally:

  ∀ a b : ℤ, Even a → Even b → Even (a + b)

Use verify_lean. The standard Mathlib proof unfolds Even as ∃ k, _ = k + k (or 2*k), then constructs the witness.`,
    expectedAnswer: "Proved by destructuring the two Even hypotheses and using the witness ka + kb.",
    maxSteps: 6,
  },

  "erdos-straus-residual-primes": {
    id: "erdos-straus-residual-primes",
    type: "OPEN PROBLEM — Erdős–Straus for primes p ≡ 1 mod 4 (the genuinely open residual)",
    difficulty: "very-hard",
    prompt: `## The residual after all prior runs

After the harness's prior runs, the Erdős–Straus conjecture is **Lean-formally proved** for these classes:

| Class | Construction |
|---|---|
| $n \\equiv 0 \\pmod 2$ (even) | $(k, 2k, 2k)$ for $n = 2k$ |
| $n \\equiv 0 \\pmod 4$ refined | $(3m, 3m, 3m)$ for $n = 4m$ |
| $n \\equiv 3 \\pmod 4$ (Mordell) | $(k+1,\\, n(k+1)+1,\\, n(k+1)(n(k+1)+1))$ for $n = 4k+3$ |
| $n \\equiv 5 \\pmod 8$ (sub-residue) | $(2(k+1),\\, n(k+1),\\, 2n(k+1))$ for $n = 8k+5$ |
| $n \\equiv 5 \\pmod{12}$ (sub-residue) | $(3t+2,\\, (t+1)n,\\, (3t+2)(t+1)n)$ for $n = 12t+5$ |
| $n \\equiv 1 \\pmod 4$ with prime factor $q \\equiv 3 \\pmod 4$ (Hasse) | scale Mordell solution for $q$ by $m = n/q$ |

Plus the supporting **scaling lemma**:
$$
\\text{If } (x, y, z) \\text{ solves } \\tfrac{4}{n} = \\tfrac{1}{x}+\\tfrac{1}{y}+\\tfrac{1}{z}, \\text{ then } (mx, my, mz) \\text{ solves } \\tfrac{4}{mn} = \\tfrac{1}{mx}+\\tfrac{1}{my}+\\tfrac{1}{mz}.
$$

## Reduction to primes

By the scaling lemma, **the residual case reduces to PRIMES $p \\equiv 1 \\pmod 4$**:

If $n \\equiv 1 \\pmod 4$ and every prime factor of $n$ is $\\equiv 1 \\pmod 4$ (the only remaining open class), write $n = p \\cdot m$ where $p$ is one such prime. Then a solution for $p$ scales to a solution for $n$. So Erdős–Straus for the residual class follows from:

**Open conjecture (the actual residual):** *for every prime $p \\equiv 1 \\pmod 4$, there exist positive integers $x, y, z$ with $\\tfrac{4}{p} = \\tfrac{1}{x} + \\tfrac{1}{y} + \\tfrac{1}{z}$.*

This is the **genuinely open** part of Erdős–Straus. The set of such primes is infinite (Dirichlet) and its density is $1/2$ among odd primes. Computer search has verified the conjecture for all such primes up to $p \\leq 10^{17}$ (Salez 2014).

## Lean starting material (re-verify these as your first calls)

Re-verify each via \`lean_define\` so they're all in scope:

\`\`\`lean
import Mathlib

structure Solution (n : ℕ) : Type where
  (x y z : ℕ)
  (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0)
  (h : 4 * x * y * z = n * (x * y + x * z + y * z))

-- Even case
theorem even_case (k : ℕ) (hk : k ≠ 0) : Solution (2 * k) :=
  { x := k, y := 2 * k, z := 2 * k,
    hx := hk, hy := mul_ne_zero (by norm_num) hk, hz := mul_ne_zero (by norm_num) hk,
    h := by ring }

-- n ≡ 3 mod 4 (Mordell)
theorem mordell_3mod4 (k : ℕ) :
    Solution (4 * k + 3) :=
  { x := k + 1,
    y := (4 * k + 3) * (k + 1) + 1,
    z := (4 * k + 3) * (k + 1) * ((4 * k + 3) * (k + 1) + 1),
    hx := by omega,
    hy := by omega,
    hz := mul_ne_zero (mul_ne_zero (by omega) (by omega)) (by omega),
    h := by ring }

-- Scaling lemma: solve for n ⇒ solve for mn
theorem solution_scale (n m : ℕ) (hm : m ≠ 0)
    (s : Solution n) : Solution (m * n) :=
  { x := m * s.x, y := m * s.y, z := m * s.z,
    hx := mul_ne_zero hm s.hx, hy := mul_ne_zero hm s.hy, hz := mul_ne_zero hm s.hz,
    h := by have := s.h; ring_nf; linarith }

-- Hasse reduction: q ≡ 3 mod 4 prime factor ⇒ Solution
theorem hasse_reduction (q m : ℕ) (k : ℕ)
    (hq : q = 4 * k + 3) (hm : m ≠ 0) : Solution (m * q) :=
  solution_scale q m hm (hq ▸ mordell_3mod4 k)
\`\`\`

## What's been tried for primes $p \\equiv 1 \\pmod 4$ — DO NOT REPRODUCE

This residual has been picked at for 78 years. Known dead ends:

1. **Polynomial identities of any degree** — Mordell (1967): impossible because $1$ is a quadratic residue mod every prime. **No linear, quadratic, or higher polynomial parameterization in $p$ can solve uniformly for $p \\equiv 1 \\pmod{4}$.**

2. **Greedy / extended-greedy** — produces 4-term expansions, not 3-term, for $p \\equiv 1 \\pmod 4$.

3. **Brute-force computer search** — already verified to $p \\leq 10^{17}$.

4. **Brauer–Manin obstruction analysis** — Bright & Loughran (2020): no obstruction exists. Local solvability is fine; if a global obstruction exists it's *deeper* than Brauer-Manin.

5. **Standard sub-residue covering systems** — Webb, Vaughan, Li, Yang, Ahmadi-Bleicher, Elsholtz pushed this technique to its natural limit. The remaining residual (primes $\\equiv 1 \\pmod 4$ NOT in any covered sub-residue) is irreducibly open.

6. **Heath-Brown's density argument** (1996) — shows failures have density $O((\\log N)^{-3})$ but doesn't eliminate any specific prime.

## Under-explored angles for the residual primes

These are angles that have either (a) recent unverified preprints, (b) Mathlib infrastructure available, or (c) been suggested in the literature but not formally executed:

### Angle A: Verify ED2 identity for specific primes (recent unverified preprint)

**ArXiv 2511.07465 (Nov 2025), unverified by peer review**, claims a constructive proof for all primes $P \\equiv 1 \\pmod 4$ via the identity
$$
(4b - 1)(4c - 1) \;=\; 4P\\delta + 1
$$
yielding a parameterization $(\\delta, b, c) \\in \\mathbb{Z}^3$ that solves Erdős–Straus.

**Concrete first task**: for each prime $p \\in \\{5, 13, 17, 29, 37, 41, 53, 61, 73, 89, 97, 101, 109, 113, 137, 149, 157, ...\\}$, find integer $(\\delta, b, c)$ satisfying ED2 and the Erdős–Straus equation. Use \`verify_smt\` or \`verify_template\` to confirm. If you find primes where ED2 fails (no integer $(\\delta, b, c)$ satisfies), that's a *counterexample to a recent preprint*, which is a real result.

### Angle B: Multiplicative lifting via Mathlib

You already have the scaling lemma. Combined with the residual reducing to primes, prove formally:

> "If for every prime $p \\equiv 1 \\pmod 4$, $\\frac{4}{p}$ has a 3-term Egyptian decomposition, then for every $n$ that's a product of primes all $\\equiv 1 \\pmod 4$, $\\frac{4}{n}$ has a 3-term Egyptian decomposition."

This is the multiplicative lift; it reduces the residual to the prime case formally. Use Mathlib's \`Nat.Prime\` machinery + induction on prime factorization. This formal reduction theorem isn't in Mathlib.

### Angle C: Combinatorial Nullstellensatz for fixed $p$

A previous run found \`MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero\` in Mathlib but didn't bridge it. The bridge: for fixed $p \\equiv 1 \\pmod 4$, recast the existence of $(x, y, z)$ as a non-vanishing polynomial question over $\\mathbb{Z}/p^k\\mathbb{Z}$ for sufficient $k$. This is technically demanding but formally Lean-able.

### Angle D: Quadratic-residue lift à la Ionascu-Wilson (2011)

For each prime $p \\equiv 1 \\pmod 4$, find a *different* prime $q$ such that $p$ is a non-quadratic-residue mod $q$ (always exists by quadratic reciprocity + density). Apply Mordell-style identity for $n \\equiv p \\pmod q$. Was theoretical in 2011; could be made constructive with computer search per $p$.

### Angle E: Empirical small-prime verification via Z3

For each prime $p \\equiv 1 \\pmod 4$ up to (say) $p = 1000$, use Z3's existential search with bounded $(x, y, z)$ to find an explicit decomposition. This is computationally easy per $p$. Empirical pattern detection might suggest a structural construction.

### Angle F: Connection to elliptic curves over $\\mathbb{Q}$

The Erdős–Straus equation $4xyz - n(yz + xz + xy) = 0$ defines an algebraic surface. For specific $n$, this surface has elliptic-curve sub-structure. Mathlib has \`EllipticCurve\` and \`AlgebraicGeometry.EllipticCurve\` infrastructure. Could rational-point analysis on the elliptic-curve fiber for primes $p \\equiv 1 \\pmod 4$ yield existence?

## Process

1. **Re-verify** the 4 prior Lean theorems (even, Mordell, scaling, Hasse) so they're in your branch's env.
2. **Pick ONE under-explored angle** (A through F above). Don't try multiple at once.
3. **For each step**, ask: is this a known result I'd be reproducing? Use the literature catalog above as your guide — DON'T attempt polynomial identities for $p \\equiv 1 \\pmod 4$, DON'T attempt Brauer-Manin, DON'T retry covering systems.
4. **Formalize what you can** in Lean. Use \`lean_search\` aggressively to find Mathlib's primitives. The harness rewards verifiable cross-disciplinary technique imports.
5. **Verify** with cross-encoding via \`verify_template\` / \`audit\` / \`review\`.
6. **Ship honestly**: the done-gate requires your final answer to substantively match verified artifacts. Don't claim more than you proved.

## Realistic outcomes

- **Most likely**: you verify the ED2 identity (angle A) for several specific small primes via Z3, confirming or surfacing a counterexample to the 2025 preprint.
- **Plausible**: you formalize the multiplicative lift (angle B) — a clean Lean theorem that's not in Mathlib.
- **Significant**: you reach a verifiable structural argument from one of angles C, D, F that goes beyond known partial results.
- **Vanishingly unlikely**: a full proof for the residual class. We're not expecting to crack this; we're looking for *any verified artifact that goes beyond the literature catalog*.

## Critical reminders

- **Don't reproduce Mordell's QR obstruction.** Polynomial identities for $p \\equiv 1 \\pmod p$ are blocked. Linear, quadratic, any degree.
- **Don't redo the Hasse reduction.** It's already proved (this run's prior result).
- **Don't redo sub-residue mod-840.** Already done.
- **Don't redo brute force at the human-feasible scale.** Already done to $10^{17}$.
- **Do consult the angle catalog** before each new turn.
- **Do verify the 2025 preprint claims empirically** — this is the most concrete novel-leaning task.

## Budget: 100 turns`,
    expectedAnswer:
      "Open. The hard core of Erdős–Straus is now reduced to: for every prime p ≡ 1 mod 4, does 4/p have a 3-term Egyptian fraction decomposition? Realistic measure of success: any verified artifact going beyond the literature catalog — empirical verification of recent unverified preprint, formalization of multiplicative lift, or genuine cross-disciplinary technique import via Mathlib infrastructure.",
    maxSteps: 100,
  },

  "erdos-straus-residual-primes-proof": {
    id: "erdos-straus-residual-primes-proof",
    type: "OPEN PROBLEM — General proof of Erdős–Straus for all primes p ≡ 1 mod 4",
    difficulty: "very-hard",
    prompt: `## Your task

**Find a general proof of the Erdős–Straus conjecture for the residual class: every prime $p \\equiv 1 \\pmod 4$.**

That is, prove (or make substantive verified progress toward proving):

> **(Open conjecture)** For every prime $p \\equiv 1 \\pmod 4$, there exist positive integers $x, y, z$ with $\\tfrac{4}{p} = \\tfrac{1}{x} + \\tfrac{1}{y} + \\tfrac{1}{z}$.

Not a verification for finitely many primes. Not a probabilistic / density statement. **A proof that quantifies uniformly over the infinite set of primes $\\equiv 1 \\pmod 4$.**

This has been open for 78 years. We are not expecting you to definitively close it in 100 turns. We **are** asking you to:

1. **Think hard** about what a proof would *structurally have to look like*, given everything that's already been ruled out.
2. **Survey** approaches from many disciplines — algebraic number theory, algebraic geometry, additive combinatorics, analytic number theory, the polynomial method, model theory, p-adic methods, modular forms, etc. — and identify which framework is most likely to yield a uniform-over-primes existence statement.
3. **Commit** to a structural plan via the \`thesis\` tool BEFORE running any verification work toward the goal. (See "Mandatory thesis-first protocol" below.)
4. **Try to formalize partial progress** in Lean / Z3 / Prolog. Any verified artifact that constitutes a *new* structural reduction or a *new* sufficient condition for the residual primes is a real result.

## Mandatory thesis-first protocol

**You MUST call \`thesis\` before any verification targeting the goal.** Without a registered thesis, the \`audit\` tool will refuse to run, and \`done\` therefore cannot succeed. This is a hard structural gate, not a suggestion.

A human mathematician doesn't write a proof before they know what they're trying to prove and what the proof skeleton looks like. The harness now enforces the same discipline. Your \`thesis\` call commits you (in writing) to:

- **goal**: the universal statement you intend to prove (e.g., "for every prime $p \\equiv 1 \\pmod 4$, $\\exists x, y, z \\in \\mathbb{N}^+$ with $4/p = 1/x + 1/y + 1/z$").
- **subClaims**: the proof skeleton, decomposed into formally verifiable steps. Each sub-claim should be small enough to attack with a Lean proof, an SMT verification of a structural lemma, or a Prolog derivation.
- **technique**: the proof framework you've chosen (e.g., "Combinatorial Nullstellensatz over $\\mathbb{Z}/p\\mathbb{Z}$", "rational-point density on the surface $4xyz = p(xy+xz+yz)$", "Frobenius-structure case split via quadratic reciprocity").
- **nonFiniteJustification**: your explicit explanation for *why this approach scales to the infinite class* — what makes the argument uniform over primes rather than just verifying instances.

You can call \`thesis\` again to update the plan as you learn what's tractable; later calls overwrite, and the old audit/review state is cleared. **A change of approach is fine and expected; an absence of approach is not.**

## Penalty for finite-instance work

**The harness now actively penalizes the failure mode of "verify small instances and frame them as a general proof."** Specifically:

- The audit's new **Check D (thesis-vs-artifact alignment)** compares your verified artifact against your registered thesis. If your thesis is universal ("for all primes $p \\equiv 1 \\pmod 4$") and your artifact is instance-only ("$p = 5$ has solution $(2, 5, 10)$"), **the audit will fail** unless your proposed answer explicitly scopes itself to the verified instances and disavows the universal claim.
- **Verifying for finitely many primes is not progress toward the goal.** Even verifying for primes up to $10^{17}$ is not progress (Salez 2014 already did that). Only artifacts that move structurally toward the universal claim count.
- If you find yourself instinctively reaching for "let me just check $p = 5$ in Z3," **stop and re-read your thesis.** Either (a) the small-instance check is a sub-claim in your thesis (e.g., a base case for an induction or a small-case verification of a lemma's hypothesis), in which case state that explicitly, or (b) it's not, and you're drifting back to the easy thing — refocus on the structural sub-claims.

The harness's prior run on this exact problem (no thesis gate) ended at 65 turns with 26 verified artifacts, **all** small-prime instance verifications, and **zero \`audit\` calls** — because the model never produced a structural argument worth auditing. This gate exists to prevent that exact failure mode.

What COUNTS as progress under the new gate:
- A Lean-formalized reduction theorem ("if $X$ holds for primes $p \\equiv 1 \\pmod 4$, then Erdős–Straus does too").
- An SMT-verified structural lemma over symbolic $p$ (e.g., a polynomial identity that holds for all $p$ in some sub-class, with $p$ a free symbolic constant — not pinned to a value).
- A Prolog derivation showing that a registered sub-claim follows from accepted premises.
- A verified counterexample to a sub-claim or to a published claim (with the counterexample explicitly tested against the published statement, not a guessed mapping).

What does NOT count:
- "$p = 5$ has solution $(2, 5, 10)$" (and similar instance verifications), unless framed as a base case in a thesis-registered induction.
- "I checked the ED2 identity for primes 5, 13, 17, ..., 157" (also done in the prior run; redundant).
- A Lean theorem that just re-states the prior verified theorems.

## Lean starting material — already proved (re-verify so they're in scope)

The harness has already produced these Lean-verified theorems. Re-verify each via \`lean_define\` so they're available for any combined argument:

\`\`\`lean
import Mathlib

structure Solution (n : ℕ) : Type where
  (x y z : ℕ)
  (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0)
  (h : 4 * x * y * z = n * (x * y + x * z + y * z))

-- Even case: n = 2k → (k, 2k, 2k)
theorem even_case (k : ℕ) (hk : k ≠ 0) : Solution (2 * k) :=
  { x := k, y := 2 * k, z := 2 * k,
    hx := hk, hy := mul_ne_zero (by norm_num) hk, hz := mul_ne_zero (by norm_num) hk,
    h := by ring }

-- n ≡ 3 mod 4 (Mordell 1967 identity)
theorem mordell_3mod4 (k : ℕ) : Solution (4 * k + 3) :=
  { x := k + 1,
    y := (4 * k + 3) * (k + 1) + 1,
    z := (4 * k + 3) * (k + 1) * ((4 * k + 3) * (k + 1) + 1),
    hx := by omega, hy := by omega,
    hz := mul_ne_zero (mul_ne_zero (by omega) (by omega)) (by omega),
    h := by ring }

-- Scaling lemma: solve for n ⇒ solve for mn
theorem solution_scale (n m : ℕ) (hm : m ≠ 0) (s : Solution n) : Solution (m * n) :=
  { x := m * s.x, y := m * s.y, z := m * s.z,
    hx := mul_ne_zero hm s.hx, hy := mul_ne_zero hm s.hy, hz := mul_ne_zero hm s.hz,
    h := by have := s.h; ring_nf; linarith }

-- Hasse reduction: any n with prime factor q ≡ 3 mod 4 is solved by scaling Mordell
theorem hasse_reduction (q m : ℕ) (k : ℕ) (hq : q = 4 * k + 3) (hm : m ≠ 0)
    : Solution (m * q) :=
  solution_scale q m hm (hq ▸ mordell_3mod4 k)
\`\`\`

Plus prior runs verified sub-residue identities for $n \\equiv 5 \\pmod 8$ and $n \\equiv 5 \\pmod{12}$ (additional partial covers, not load-bearing for the residual).

**Key structural fact**: by the scaling lemma + Hasse reduction, the conjecture for all $n \\equiv 1 \\pmod 4$ reduces to the conjecture for primes $p \\equiv 1 \\pmod 4$. **That reduction is your starting point.**

## What's been tried — KNOWN DEAD ENDS, do not reproduce

This residual is the hard core. Every "obvious" technique has been tried. Spend zero turns on:

1. **Polynomial identity in $p$** of any degree — *Mordell 1967*: blocked because $1$ is a quadratic residue mod every prime. No linear, quadratic, or higher polynomial parameterization in $p$ can solve uniformly for $p \\equiv 1 \\pmod 4$. **This rules out the most natural "guess a closed form" attack.**

2. **Sub-residue covering systems** — Webb, Vaughan, Li, Yang, Ahmadi-Bleicher, Elsholtz pushed this to its limit. Every sub-residue mod $M$ (for any $M$) that's covered by an explicit Mordell-style identity has been catalogued. The remaining residual is not a sub-residue; it can't be punctured away.

3. **Greedy / extended-greedy** — produces 4-term decompositions, not 3-term, for $p \\equiv 1 \\pmod 4$.

4. **Brauer–Manin obstruction** — *Bright & Loughran 2020*: no obstruction. Local solvability is fine; if a global obstruction exists, it lives *deeper* than Brauer-Manin.

5. **Brute-force search** — Salez 2014 verified the conjecture for all primes up to $10^{17}$. A purely computational attack with no structural insight gains nothing.

6. **Heath-Brown 1996 density** — failures have density $O((\\log N)^{-3})$, but no specific prime is excluded. Density-1 results (incl. *arXiv 2602.20036v2*) don't close any individual prime.

7. **Recent unverified preprint** (*arXiv 2511.07465*, Nov 2025) claims a constructive proof via the ED2 identity $(4b-1)(4c-1) = 4P\\delta + 1$. **Unverified by peer review.** The harness's prior run verified this identity holds for the listed primes $p \\in \\{5, ..., 157\\}$ — but did NOT verify the preprint's general construction works for all primes $\\equiv 1 \\pmod 4$. That gap is the core open question.

## Think first: what would a general proof actually require?

Before picking a technique, work out the proof skeleton. A general proof of the residual must:

- **Quantify uniformly** over an infinite set of primes (cannot be a finite case analysis).
- **Use prime structure beyond polynomial identity in $p$** (Mordell's obstruction blocks the polynomial route).
- **Either** (a) provide an existential argument (witness exists for each $p$) **without** an explicit closed form in $p$, **or** (b) provide a closed form parameterized by *more than just $p$* (e.g., by $p$ and a quadratic-residue or Frobenius structure mod $p$).

Concretely, the proof shape probably looks like one of:

**Shape (i) — Existence via algebraic-geometry counting.** The Erdős–Straus equation $4xyz = p(xy + xz + yz)$ defines a surface $S_p$ in $\\mathbb{P}^3$. Show $S_p(\\mathbb{Q})$ contains a positive-orthant rational point for every prime $p \\equiv 1 \\pmod 4$. Tools: rational-point density, Hasse principle conditional, elliptic-fiber existence, Manin's conjecture flavor.

**Shape (ii) — Existence via additive combinatorics / polynomial method.** Recast the existence of $(x, y, z)$ as a non-vanishing question for a polynomial $\\Phi_p$ over $\\mathbb{Z}/p\\mathbb{Z}$ or $\\mathbb{Z}/p^k\\mathbb{Z}$. Apply Combinatorial Nullstellensatz, Croot-Lev-Pach style polynomial method, or character sum estimates. Mathlib has \`MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero\`.

**Shape (iii) — Existence via Frobenius / quadratic-residue structure.** For each $p \\equiv 1 \\pmod 4$, find a *secondary* parameter $r$ depending on the QR structure of $p$ (e.g., a small prime $q$ such that $p$ is a non-residue mod $q$) and exhibit a solution parameterized by $(p, r)$. The 2011 Ionascu-Wilson sketch goes here. Quadratic reciprocity guarantees such $r$ exists; the question is whether a solution can be uniformly extracted.

**Shape (iv) — Existence via analytic number theory.** Use sieve methods, character sum estimates, or circle method to show that the count of solutions $N_p$ is positive for every $p$ — not just on average. Heath-Brown's density argument suggests $N_p$ is large *on average*; the open question is the worst case.

**Shape (v) — Reduction to a different open problem already known to be tractable.** Reduce the residual to (e.g.) a statement about modular forms, an L-function non-vanishing, or a specific Diophantine question for which Mathlib has more developed infrastructure.

**Pick a shape, justify why it's the most plausible attack given the obstructions, and execute as far as you can.**

## Cross-disciplinary techniques you might pull from

The harness rewards verifiable cross-disciplinary technique imports. Consider:

- **Algebraic geometry**: rational points on surfaces, Hasse principle, Manin's conjecture, elliptic-curve fibers, blow-ups, Kummer surfaces. Mathlib: \`AlgebraicGeometry\`, \`EllipticCurve\`.
- **Additive combinatorics**: Combinatorial Nullstellensatz, polynomial method (Croot-Lev-Pach), character sum estimates, Plünnecke-Ruzsa, sumset growth. Mathlib: \`MvPolynomial\`, \`Finset.sum\`.
- **Analytic number theory**: circle method, large sieve, Selberg sieve, character sums, exponential sums, distribution of primes in arithmetic progressions. Mathlib: \`Nat.Prime\`, \`DirichletCharacter\` (limited).
- **p-adic methods**: Hensel's lemma, $p$-adic interpolation, Iwasawa theory. Mathlib: \`Padic\`.
- **Modular forms / L-functions**: half-integer weight forms, Shimura correspondence, GRH-conditional bounds. Mathlib: \`ModularForm\` (limited).
- **Model theory**: definability over $\\mathbb{Z}$, decidability arguments, transfer principles (Ax-Kochen-Ershov for $p$-adic).
- **Reverse mathematics**: identify the proof-theoretic strength a uniform-over-primes existence proof would require.

The hardest constraint: **whatever technique you pick, you should be able to verify (parts of) the argument using the harness's tools** — Lean for formalization, Z3 for finite instance checks and SMT verification, Prolog for combinatorial / structural reasoning. Pick a route where verification is *possible*, not one that requires unbounded analytic apparatus.

## Process

1. **First turn**: re-verify the 7 starting Lean theorems via \`lean_define\` so they're in scope.

2. **Think structurally** about the proof skeleton. Which of shapes (i)–(v) — or which combination — is most plausible given the obstructions catalogued above? **Reason it out in prose first.** The harness rewards *honest reasoning about what's hard*, not bluster.

3. **Call \`thesis\`** to commit to your structural plan: \`{goal, subClaims, technique, nonFiniteJustification}\`. This is the gate — without it, audit (and therefore done) cannot fire. The thesis is your proof skeleton in writing; the audit will hold each verified artifact against it.

4. **Pick ONE attack** and pursue it. Don't bounce between shapes. (You can call \`thesis\` again later to refine, but don't oscillate.)

5. **Decompose into the registered sub-claims** and attack them: each verified artifact should advance a specific sub-claim. State which sub-claim each \`verify_*\` call targets in your prose.

6. **Verify aggressively**. Use \`audit\` and \`review\` to check your encoding and your scope before claiming progress. The audit gate enforces honest framing: don't ship a misframed claim, and don't ship a finite-instance check as a general proof.

7. **If you hit a wall**, document it as a verified negative result. "Approach X fails because of obstruction Y" is a real contribution if Y is novel or not in the literature catalog above. Update your thesis to reflect the change in approach and continue. Negative results require the same level of rigor as positive ones.

## Realistic outcome targets

Ranked by likelihood × impact:

- **Likely + meaningful**: a Lean-formalized *new structural reduction* — e.g., reducing the residual to a simpler / more tractable open problem. Even a partial reduction is shippable.
- **Plausible + meaningful**: a Lean-formalized *new sufficient condition* — e.g., "if Erdős-Straus holds for primes $p \\equiv 1 \\pmod 4$ with such-and-such QR structure, it holds for all." Identifying *which* sub-class is the actual hard core is progress.
- **Plausible + significant**: empirical disconfirmation of a published claim (e.g., counterexample to a sub-claim of arXiv 2511.07465 or 2602.20036v2), with rigorous SMT verification.
- **Unlikely + significant**: a verified general proof. Don't promise this; aim for the structural reductions and let any general proof emerge from there.

## Critical reminders

- **The audit gate is active**. Your final \`done()\` answer must pass thesis-vs-problem reflection. If you ship "I verified X for 17 primes" framed as "I proved the conjecture", the audit will reject it. Frame honestly.
- **The done-gate token check is active**. Final answer must reference the actual verified artifacts.
- **Don't reproduce known dead ends**. The list above is exhaustive for the obvious attacks. Use it.
- **Don't conflate empirical instance verification with general proof**. Verifying for primes up to 157 is *not* a proof. Both kinds of artifact are valuable, but their scope is very different — the audit will catch this.
- **Don't underestimate the difficulty**. 78 years, every Field Medal-adjacent technique applied. If your approach feels easy, you're missing an obstruction. Stop and re-check.
- **Do think hard**. The harness has the budget for genuine reasoning. Use turns on structural thinking, not just tool-spamming.

## Budget: 100 turns

You have 100 turns to make your best honest attempt at this 78-year-old open problem. Aim for verified structural progress. Ship honestly.`,
    expectedAnswer:
      "OPEN. The expected outcome is verified structural progress — a new reduction, a new sufficient condition, or a verified disconfirmation of a recent preprint claim — not a full general proof. Success is measured by: (a) honest framing under the audit gate; (b) any Lean/Z3-verified artifact that goes beyond the literature catalog; (c) explicit identification of which sub-class of primes ≡ 1 mod 4 is the actual residual hard core after applying every available reduction.",
    maxSteps: 100,
  },

  "erdos-straus-ed2-general-disproof": {
    id: "erdos-straus-ed2-general-disproof",
    type: "OPEN — Disprove arXiv 2511.07465's general δ-parameterized ED2 method (or sharply narrow it)",
    difficulty: "very-hard",
    prompt: `## Your task

**Build on the prior verified result and attempt to disprove (or sharply narrow) the GENERAL δ-parameterized ED2 method of arXiv 2511.07465.**

The prior run established a small but real result:

> **(Prior verified result, Z3 UNSAT, audit-gate approved)**
> The equation $4bc - b - c = 13$ has no positive integer
> solutions. Equivalently, $(4b - 1)(4c - 1) = 53$ has no
> positive integer solutions (since 53 is prime). Hence the
> $\\delta = 1$ specialization of arXiv 2511.07465's ED2 method
> fails for $p = 13$.

That disproved the simplest case ($\\delta = 1$). The full
preprint claim is **$\\delta$-parameterized**, and the model in
the prior run did NOT address the general case. Your job is to
push that result as far as it will honestly go.

## What the preprint actually claims

ArXiv 2511.07465 (Nov 2025), unverified by peer review, claims:

> For every prime $P \\equiv 1 \\pmod 4$, there exist positive
> integers $b, c, \\delta$ such that
> $$(4b - 1)(4c - 1) = 4P\\delta + 1$$
> AND $A := bc / \\delta$ is a positive integer, in which case
> $\\frac{4}{P} = \\frac{1}{A} + \\frac{1}{bP} + \\frac{1}{cP}$
> is a valid 3-term Egyptian decomposition.

So a "valid ED2 representation" of prime $P$ is a triple
$(\\delta, b, c) \\in \\mathbb{Z}^3_{>0}$ satisfying:

1. **Diophantine constraint**: $(4b - 1)(4c - 1) = 4P\\delta + 1$.
2. **Mod-4 constraint**: $4b - 1 \\equiv 3 \\pmod 4$ and
   $4c - 1 \\equiv 3 \\pmod 4$ (automatic from positivity of $b, c$).
3. **Integrality constraint**: $\\delta \\mid bc$ (so that $A = bc/\\delta$ is a positive integer).

If condition 3 fails for the $(b, c)$ extracted from the
factorization, that $\\delta$ doesn't yield an Erdős–Straus
solution.

## What's been checked so far (the foothold)

The prior run verified the following for $p = 13$:

| $\\delta$ | $4P\\delta + 1$ | Factorizations into $(4b-1)(4c-1)$ with $b, c \\geq 1$ | $\\delta \\mid bc$? | ED2 valid? |
|---|---|---|---|---|
| 1 | 53 | none (53 is prime; $1 \\cdot 53$ gives $b = 1/2$) | n/a | **NO** (verified UNSAT) |

That's the entire data set. Everything beyond is open territory
for this run.

## Your concrete tasks

The space of attacks is now narrow and tractable. Pick one (or
combine):

### Task A — Sharpen the prior disproof

For $p = 13$, exhaustively check $\\delta \\in \\{2, 3, 4, \\ldots, K\\}$ for some bound $K$. For each $\\delta$:
1. Enumerate factorizations $(4b - 1)(4c - 1) = 52\\delta + 1$ with $b, c \\geq 1$.
2. For each factorization, check $\\delta \\mid bc$.

If you find a $\\delta$ where ED2 succeeds, then $p = 13$ is **NOT** a counterexample to the general method, and the prior run's framing needs to be sharply narrowed (only the $\\delta = 1$ case is disproved — which the audit already noted).

If you find no $\\delta$ in some explicit range, say what range, and what would be needed to extend the disproof (an asymptotic bound on the necessary $\\delta$ in terms of $P$).

**This task can be done with Z3 in a single \`verify_smt\` per $\\delta$, or one parameterized query over $\\delta$ in a bounded range.**

### Task B — Find a prime where ED2 fails for all bounded $\\delta$

Pick a candidate prime $p \\in \\{17, 29, 37, 41, 53, 61, 73, 89, 97, 101, 109, 113, 137, 149, 157\\}$ (the residual list from prior runs) and exhaustively check ED2 across $\\delta \\in [1, K]$ for some bound $K$. If ED2 succeeds for some $\\delta$ in your range, that prime is NOT a counterexample. If it fails throughout $[1, K]$, ship the counterexample with explicit $K$ and the structural reason (e.g., "$4P\\delta + 1$ is prime for $\\delta \\in [1, K]$" or "the factorizations all violate condition 3").

Caveat: the prior runs verified explicit Erdős–Straus solutions
for these primes via OTHER methods, so a prime that fails ED2
across all $\\delta$ doesn't disprove Erdős–Straus — it disproves
that ED2 is a complete method.

### Task C — Identify a structural obstruction to ED2

Either:
1. **Find a prime structure (e.g., a Frobenius / quadratic-residue pattern) that makes ED2 always fail** — would give an infinite family of counterexamples to the preprint.
2. **OR formally prove that ED2 cannot be a complete method**, e.g., a counting argument showing the set of primes with valid ED2 representations has density $< 1$ in the residue class.
3. **OR formally verify ED2 is complete for some sub-residue** (e.g., $p \\equiv 1 \\pmod{12}$) — would constitute a positive partial result for the preprint.

### Task D — Lean-formalize the prior result

The prior run's UNSAT verification was Z3-only. For this run,
formalize in Lean: state the lemma "$(4b-1)(4c-1) = 53$ has no
positive integer solutions" and prove it (53 is prime, only
factorization is $1 \\cdot 53$, so $4b - 1 = 1 \\Rightarrow b = 1/2$).
Then state and prove "the $\\delta = 1$ ED2 specialization fails
for $p = 13$" as a Lean theorem. This would be a small but
genuine Lean contribution — a verified obstruction to a
specific construction.

## Mandatory thesis-first protocol

**You MUST call \`thesis\` before any verification toward the goal.** This is the same gate from the prior run, and it worked — the prior run's B5 used three thesis calls to refine its plan as it learned what was tractable. Do the same.

Your thesis must include:
- **goal**: which task above (A / B / C / D) you're attacking, and the universal statement implied.
- **subClaims**: the proof skeleton, decomposed into individually verifiable steps. For Task A, each $\\delta$ check could be a sub-claim; for Task C, the structural obstruction needs to be stated as a sub-claim.
- **technique**: the framework (Z3 enumeration over $\\delta$, structural analysis of $4P\\delta + 1$, Lean formalization, etc.).
- **nonFiniteJustification**: why your approach yields a result whose scope matches the goal. For Task B, "$K = 100$ exhausts the relevant $\\delta$ range because [reason]". For Task A, "showing failure for $\\delta \\leq K$ means the prior counterexample extends to" [something]. State the limits of your conclusion explicitly so the audit doesn't catch you overclaiming.

## What COUNTS as progress

Building on the prior δ=1 disproof, the gate now treats these as real progress:

1. **Extension of the disproof** — verified UNSAT for $p = 13$ across $\\delta \\in [1, K]$ for any explicit $K \\geq 2$, with a structural argument for why the bound $K$ is meaningful.
2. **Sharpening of the disproof** — verified that the prior result's framing was too strong (e.g., $\\delta = 2$ gives a valid $(b, c, \\delta)$ for $p = 13$, even if integrality fails). This is a NEGATIVE result on the prior run's framing, but it's verified, audit-passable, and meaningful.
3. **A new prime counterexample** — verified UNSAT for some $p \\neq 13$ across $\\delta \\in [1, K]$.
4. **A structural obstruction** — a Lean-formalized lemma that captures why ED2 fails for an infinite family.
5. **A positive result for the preprint** — verified ED2 is complete for some sub-residue of primes $\\equiv 1 \\pmod 4$.

## What does NOT count

- Re-verifying the $\\delta = 1$ disproof for $p = 13$ (the prior run already did that).
- Verifying Erdős–Straus solutions for individual primes via OTHER methods (those are known and don't speak to ED2).
- Claiming "ED2 is disproved" without bounding $\\delta$.
- Single-$\\delta$ checks framed as general-method disproofs (the prior run already did that and the audit's Check D will catch it).

## Lean starting material — already proved (re-verify as you need)

The prior runs verified these. Re-define via \`lean_define\` if your work needs them in scope:

\`\`\`lean
def IsSolution (n : ℕ) : Prop :=
  ∃ (x y z : ℕ) (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0),
    4 * x * y * z = n * (x * y + x * z + y * z)

-- Even, mod-4 = 3 (Mordell), scaling, Hasse-reduction lemmas
-- (full statements in erdos-straus-residual-primes-proof prompt
-- if you need them).
\`\`\`

## SMT / Z3 patterns useful for this problem

For Task A — bounded enumeration over $\\delta$:
\`\`\`
(declare-const delta Int)
(declare-const b Int)
(declare-const c Int)
(assert (>= delta 1)) (assert (<= delta 50))
(assert (>= b 1)) (assert (>= c 1))
(assert (= (* (- (* 4 b) 1) (- (* 4 c) 1)) (+ (* 4 13 delta) 1)))
(assert (= (mod (* b c) delta) 0))   ; integrality of A = bc/delta
(check-sat)
\`\`\`
SAT here means a valid ED2 triple exists for $p = 13$ within
$\\delta \\leq 50$ — the prior result is overstated. UNSAT means
the disproof extends to that range.

For Task B — same template, swap 13 for another prime.

For both: **declare \`delta\` as a free Int and let Z3 search**, but **always pin the prime $P$ explicitly** so the artifact is about a specific prime, not a wildcard.

## Critical reminders

- **The thesis gate is active.** No audit / done without a thesis. The prior run engaged with it productively.
- **The audit's Check D is active.** Universal-thesis + instance-only artifact = audit FAIL unless you scope explicitly. Frame your thesis to MATCH what your verified artifact actually shows.
- **The prior framing was slightly overclaimed.** Don't repeat that — be explicit about $\\delta$ scope, the bound $K$, and what range was actually checked.
- **Z3 is fast on this** — bounded $\\delta$ enumerations of size 50 take milliseconds. You can afford broad sweeps.
- **Lean formalization (Task D) is a clean small win** — if the structural attacks don't pan out, ship a Lean formalization of the prior Z3 result. Verified is verified, even when narrow.

## Budget: 100 turns

Sharper than last time. The space is now narrow and tractable. Pick a task, set a thesis, attack it, ship honestly.`,
    expectedAnswer:
      "OPEN. Expected outcome: a verified extension of the prior δ=1 disproof — either (a) Z3 UNSAT for p=13 across an explicit larger δ range, (b) a new prime counterexample, (c) a verified structural obstruction, (d) a verified narrowing showing the prior framing was overstated, or (e) a Lean formalization of the prior result. The audit's Check D will reject any framing that overstates δ-scope.",
    maxSteps: 100,
  },

  "erdos-straus-universal-thesis": {
    id: "erdos-straus-universal-thesis",
    type: "OPEN — Attempt a UNIVERSAL claim about Erdős–Straus / ED2 (in principle, not by enumeration)",
    difficulty: "very-hard",
    prompt: `## Your task — a universal claim, not a list of primes

**Attempt to prove (or disprove) a UNIVERSAL claim about the Erdős–Straus residual class. Not for a fixed list of primes — for ALL primes $p \\equiv 1 \\pmod 4$ uniformly, or for an explicit infinite sub-family.**

Verifying $4/p$ has a 3-term Egyptian decomposition for one more prime is not the goal here. **The goal is a structural statement that's true (or false) in principle, for an infinite class.** The model should produce either:

- A verified universal theorem ("for all primes $p$ in class $C$, [property]") — a real proof.
- A verified universal disproof ("there is no proof of [statement] using only [tools / framework $F$]") — a meta-result.
- A verified reduction ("[universal claim about Erdős–Straus] $\\Leftrightarrow$ [easier-to-attack universal claim about $F$]") — pushes the problem somewhere new.
- A verified obstruction ("any proof of [claim] in framework $F$ requires [structural ingredient missing in $F$]") — narrows the search space honestly.

Pick any of these. **Be creative about the thesis.** Combine techniques from disciplines that haven't been combined here before. The harness rewards verified structural results; it penalizes finite-instance enumeration framed as general claims (audit Check D will catch it).

## Verified knowledge to start from

The harness has, across prior runs, already produced these verified results. **Re-state them as Lean axioms / definitions if your work needs them in scope** (use \`lean_define\`):

### Lean-verified: residue-class coverage

\`\`\`lean
def IsSolution (n : ℕ) : Prop :=
  ∃ (x y z : ℕ) (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0),
    4 * x * y * z = n * (x * y + x * z + y * z)
\`\`\`

| Class | Verified construction |
|---|---|
| $n \\equiv 0 \\pmod 2$ | $(k, 2k, 2k)$ for $n = 2k$ |
| $n \\equiv 0 \\pmod 4$ | $(3m, 3m, 3m)$ for $n = 4m$ |
| $n \\equiv 3 \\pmod 4$ (Mordell 1967) | $(k+1, n(k+1)+1, n(k+1)(n(k+1)+1))$ for $n = 4k+3$ |
| $n \\equiv 5 \\pmod 8$ | $(2(k+1), n(k+1), 2n(k+1))$ for $n = 8k+5$ |
| $n \\equiv 5 \\pmod{12}$ | $(3t+2, (t+1)n, (3t+2)(t+1)n)$ for $n = 12t+5$ |
| $n$ has prime factor $q \\equiv 3 \\pmod 4$ | scale Mordell's solution for $q$ by $m = n/q$ (Hasse reduction) |

Plus the **scaling lemma**: $(x,y,z)$ solves $4/n$ ⇒ $(mx,my,mz)$ solves $4/(mn)$.

**Combined consequence:** the Erdős–Straus conjecture for the entire residual class reduces to **primes $p \\equiv 1 \\pmod 4$** with all prime factors of $n$ in $\\{p : p \\equiv 1 \\pmod 4\\}$.

### Z3-verified: ED2 instance (latest run)

The arXiv 2511.07465 ED2 method is $\\delta$-parameterized: $(4b-1)(4c-1) = 4P\\delta + 1$, $A = bc/\\delta$, $\\frac{4}{P} = \\frac{1}{A} + \\frac{1}{bP} + \\frac{1}{cP}$.

The latest run **verified one ED2 instance**: $(b, c, \\delta) = (2, 4, 2)$ is a valid ED2 triple for $p = 13$. Diophantine constraint: $7 \\cdot 15 = 105 = 4 \\cdot 13 \\cdot 2 + 1$. Integrality: $\\delta = 2$ divides $bc = 8$. The induced Erdős–Straus solution is the classical $(4, 26, 52)$.

That instance does NOT generalize to a proof of ED2 completeness — it's one cell in an infinite table. **Closing the universal claim "ED2 succeeds for every prime $p \\equiv 1 \\pmod 4$" would prove the Erdős–Straus residual.**

## Approaches that have already been tried — DO NOT REPRODUCE

The residual class has been picked at for 78 years. Spend zero turns on:

1. **Polynomial identity in $p$ of any degree** (Mordell 1967): blocked because $1$ is a quadratic residue mod every prime $p \\equiv 1 \\pmod 4$. No closed form in $p$ alone solves uniformly.
2. **Sub-residue covering systems** (Webb, Vaughan, Li, Yang, Ahmadi-Bleicher, Elsholtz): pushed to limit. Remaining residual is irreducibly open under this technique.
3. **Greedy / extended-greedy**: produces 4-term, not 3-term.
4. **Brauer–Manin obstruction analysis** (Bright & Loughran 2020): no obstruction. Local solvability fine.
5. **Brute-force search**: Salez 2014 verified to $p \\leq 10^{17}$.
6. **Heath-Brown 1996 density**: failures have density $O((\\log N)^{-3})$ but doesn't pin down any specific prime.
7. **Density-1 Hasse-style results** (arXiv 2602.20036v2): cover almost all primes ≡ 1 mod 4 but the residual is still infinite.
8. **Single-$\\delta$ ED2 disproof** (the harness's own prior run): we now know $\\delta = 1$ fails for $p = 13$ but $\\delta = 2$ succeeds. The general-$\\delta$ method is still standing.
9. **Finite verification of more primes**: would just be more cells — does not yield a universal claim.

## Where creativity is needed

The harness has not seen any of these *combinations* tried — these are open avenues for novel theses:

### Avenue I — Density / counting arguments
Use analytic number theory to bound the density of primes $p \\equiv 1 \\pmod 4$ for which **no** $\\delta \\leq f(p)$ yields a valid ED2 representation. If you can prove this density is zero (i.e., every prime $\\equiv 1 \\pmod 4$ has an ED2 representation eventually), Erdős–Straus is closed for the residual. Tools: Dirichlet's theorem on primes in APs, sieve methods, character sums, distribution of products $4P\\delta+1$ over a search range.

### Avenue II — Existence-of-good-factorization
Recast ED2's success at prime $P$ as: **does there exist $\\delta \\geq 1$ such that $4P\\delta + 1$ has a factorization $(4b-1)(4c-1)$ with both factors $\\equiv 3 \\pmod 4$ AND $\\delta \\mid bc$?** This is purely a question about multiplicative structure of integers near $4P\\delta+1$. Known related results: average number of divisors with constraints, Erdős–Ko–Rado-style structure, multiplicative independence of $4P\\delta+1$ for varying $\\delta$.

If you can prove *some* $\\delta \\leq P^c$ (for a small constant $c$) **always** yields such a factorization, you've closed Erdős–Straus.

### Avenue III — Combinatorial Nullstellensatz over $\\mathbb{F}_p$
For fixed prime $p$, the Erdős–Straus equation defines a polynomial system over $\\mathbb{F}_p$. Combinatorial Nullstellensatz (Alon 1999) gives non-vanishing conditions. Mathlib has \`MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero\`. Bridging this to ED2 requires constructing the right polynomial; bridging the polynomial-method conclusion to "ED2 admits a solution" is the open structural step. **No prior run has formalized this bridge.**

### Avenue IV — Probabilistic / random-model proofs
Model $4P\\delta+1$ as "a random integer of size $\\sim 4P\\delta$". Random integers of that size have an expected number of divisors $\\sim \\log(4P\\delta)$ and an expected number of factor pairs of any specific residue class $\\sim \\log(4P\\delta) / 4$. A heuristic count suggests ED2 should succeed for $\\delta \\sim \\log P$. **Making this rigorous** — turning the heuristic into a proof for primes — would be a real result. Tools: large-deviation bounds, Erdős–Kac-style theorems, Bombieri–Vinogradov.

### Avenue V — Reverse mathematics / proof-theoretic content
Ask: **what's the proof-theoretic strength of "Erdős–Straus residual"?** Is it provable in $\\mathrm{PA}$? In $\\mathrm{RCA}_0 + \\mathrm{WKL}$? If you can identify a sub-system in which a proof would exist, you've narrowed the search; if you can show no proof in a specific sub-system suffices, you've narrowed the search differently. This is meta but verifiable. Mathlib has limited reverse-math infrastructure but the proof-theoretic content can be argued informally and shipped as a Lean axiom.

### Avenue VI — Connection to elliptic curves / modular forms
The Erdős–Straus equation $4xyz = p(yz + xz + xy)$ defines a surface; for fixed $p$ this surface has elliptic-curve fibers. Mathlib has \`EllipticCurve\` and \`AlgebraicGeometry.EllipticCurve\` infrastructure. **No prior run has tried**: rational-point analysis on these fibers as $p$ varies over primes $\\equiv 1 \\pmod 4$. Use: Mordell-Weil, height bounds, Birch–Swinnerton-Dyer (heuristic).

### Avenue VII — Cross-disciplinary technique import
Pull a technique from a *different field entirely* and apply it. Examples that have NOT been tried in this thread:
- **Information-theoretic argument** — entropy lower bounds on the number of integer solutions to certain Diophantine equations (à la PFR / Bourgain–Gamburd).
- **Ergodic theory** — recurrence of Diophantine systems under group actions.
- **Topology** — local-global principles via étale cohomology (deeper than Brauer-Manin which is ruled out).
- **Quantum computing analogy** — the ED2 search resembles a structured-search problem; there may be a classical-complexity statement to make about ED2's verifiability.
- **Sieve theory specifically Selberg's $\\Lambda^2$** — bound the number of primes WITHOUT an ED2 representation in $\\delta \\leq P^c$, hope to push to zero.

These are starting points. **Better: invent a thesis nobody has tried.** A novel cross-disciplinary thesis that's even partially verifiable counts as real progress under the audit gate.

## Mandatory thesis-first protocol (from prior runs)

You MUST call \`thesis\` BEFORE any verification toward the goal. Without one, \`audit\` (and therefore \`done\`) cannot fire. Your thesis must include:

- **goal**: the universal statement you're attacking. Make it explicit and quantifier-clear ("for every prime $p \\equiv 1 \\pmod 4$, ..." or "there exists no ... for any prime in class $C$").
- **subClaims**: the proof skeleton. Each entry is one verifiable structural step. **Sub-claims that just verify finite instances do not count toward a universal goal.** A sub-claim like "verify the lemma symbolically in Z3 with $p$ a free symbolic constant" is fine; "verify for $p = 13$" is finite-only.
- **technique**: the framework — name it precisely. "Combinatorial Nullstellensatz over $\\mathbb{F}_p$ via Mathlib's existing nullstellensatz lemma" is a precise technique. "Number theory" is not.
- **nonFiniteJustification**: why the technique scales to the infinite class. **Be specific.** Examples that pass: "the Z3 query treats $p$ as a free symbolic Int with \`(forall ((p Int)) (=> (and (> p 0) (= (mod p 4) 1)) ...))\`, so SAT/UNSAT is over all primes." Examples that fail: "by analogy" / "I'll generalize later."

## What COUNTS as progress under the audit gate

- A **Lean-verified theorem** quantifying over all primes $p \\equiv 1 \\pmod 4$ (or an explicit infinite sub-family).
- A **Z3-verified universally-quantified lemma** with $p$ as a free symbolic Int (not pinned to a specific value).
- A **verified reduction**: "if $X$ holds for all primes $\\equiv 1 \\pmod 4$ then ED2 succeeds for all primes $\\equiv 1 \\pmod 4$" — Lean lemma.
- A **verified meta-statement**: "framework $F$ cannot prove [universal claim] because $F$ lacks $X$" — formalized argument, ideally in Lean.
- A **verified obstruction**: "any proof of [universal claim] requires [non-trivial structural ingredient]" — formalized.
- A **density / counting bound**: "the set of primes $\\equiv 1 \\pmod 4$ for which ED2 fails up to $\\delta = f(p)$ has density at most [explicit bound]" — formalized.

## What does NOT count

- Verifying ED2 (or any partial result) for any specific prime or finite list.
- Re-stating the Lean theorems already verified above.
- A verbal / hand-waved universal claim with no formal artifact.
- A claim of universality whose verified artifact is instance-only — audit Check D will reject this.

## Process

1. **Spend the first 1–2 turns thinking** about which avenue (I–VII or your own) is most plausible given the obstructions catalogued. Write your reasoning explicitly in prose. Don't tool-call yet.
2. **Call \`thesis\`** with your structural plan. The goal must be universal; the sub-claims must be structural; the non-finite justification must be specific.
3. **Decompose into the registered sub-claims** and attack each. Use Z3 with $p$ as a free symbolic Int when possible. Use Lean for structural / inductive arguments. Use Prolog for combinatorial structure proofs.
4. **If you hit a wall, update your thesis** and continue. A change of approach is fine; finite-instance drift is not.
5. **Audit honestly**. The audit's Check D specifically watches for "claimed universal but verified instance-only." Frame the answer to match what's actually verified.

## Critical reminders

- **The audit gate is active and Check D is sharp.** Universal thesis + instance-only artifact = audit FAIL. Frame your thesis to MATCH what your verified artifact actually shows. If you discover the universal goal is too ambitious, revise the thesis to a verified narrower claim — that's still real progress.
- **Z3 over symbolic primes:** \`(declare-const p Int) (assert (and (> p 0) (= (mod p 4) 1)))\` plus quantifier instantiation. UNSAT here is a universal claim over the residue class. Use this pattern to make Z3 work for universal claims rather than instances.
- **Lean for genuine generality:** if your structural argument needs induction or quantifier alternation that Z3 struggles with, formalize in Lean. The harness has Lean + Mathlib + a stateful proof environment.
- **Don't promise more than you verified.** A verified \`reduction\` ("[claim A] reduces to [claim B]") is a real result even if [claim B] is itself unproved. Ship the reduction, scope your answer to "I verified this reduces to B; B is open."
- **Negative / impossibility results count.** "I tried $X$, it provably can't work because of $Y$ — verified" is a real ship.

## Realistic outcomes (ranked by likelihood × value)

- **Likely + meaningful**: a Lean-verified reduction theorem (Erdős–Straus residual ⇒ some easier-to-state structural claim). Even partial.
- **Plausible + meaningful**: a Z3-verified universal lemma over symbolic $p$ that constitutes a sufficient condition for a sub-class.
- **Plausible + significant**: a verified obstruction proving no proof in [framework] suffices.
- **Vanishingly unlikely + transformative**: a full proof of the Erdős–Straus residual. Don't promise; aim for the structural intermediate steps.

## Budget: 100 turns

Be creative. Be honest. Set a universal thesis. Verify what you actually verify. Ship narrow if needed.`,
    expectedAnswer:
      "OPEN. Expected outcome: a verified universal claim — a structural reduction, a verified sufficient condition, a verified obstruction, or a verified meta-statement (e.g., \"framework F cannot suffice because Y\"). Success measured by: (a) thesis is genuinely universal (not finite); (b) verified artifact's scope matches the thesis's scope (audit Check D enforces); (c) novel cross-disciplinary technique import or original thesis structure (not a retread of the catalogued dead ends).",
    maxSteps: 100,
  },

  "erdos-straus-mod1-informed": {
    id: "erdos-straus-mod1-informed",
    type: "OPEN PROBLEM — Erdős–Straus for n ≡ 1 mod 4 (literature-informed)",
    difficulty: "very-hard",
    prompt: `## Status of $n \\equiv 1 \\pmod 4$

A previous run formally proved Erdős–Straus for $n \\equiv 0, 2, 3 \\pmod 4$ in Lean 4 + Mathlib (commit a4c19fd). The remaining $n \\equiv 1 \\pmod 4$ class is the historically hardest and remains open in general.

A subsequent run attempted "creative" approaches and produced a Z3-verified result that no linear-in-$n$ integer parameterization solves the equation identically for $n \\equiv 1 \\pmod{24}$. Literature search confirmed this is a special case of **Mordell (1967)**, who proved the more general theorem that no polynomial identity (of any degree) can cover $n \\equiv r \\pmod p$ when $r$ is a quadratic residue mod $p$. Since $1 = 1^2$ is always a QR, polynomial identities CAN'T crack this class.

This run picks up where that one left off, with **explicit literature awareness** so we don't reproduce known results or attempt known-failed approaches.

## Verified prior work (re-verify as your first move)

The shared structure:

\`\`\`lean
import Mathlib

structure Solution (n : ℕ) : Type where
  (x y z : ℕ)
  (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0)
  (h : 4 * x * y * z = n * (x * y + x * z + y * z))
\`\`\`

Already proved:

- **$n \\equiv 0 \\pmod 2$** (even case): $(x, y, z) = (k, 2k, 2k)$ for $n = 2k$
- **$n \\equiv 0 \\pmod 4$**: $(3m, 3m, 3m)$ for $n = 4m$
- **$n \\equiv 3 \\pmod 4$**: $(k+1, n(k+1)+1, n(k+1) \\cdot (n(k+1)+1))$ for $n = 4k+3$ (Mordell/Webb)
- **$n \\equiv 5 \\pmod 8$**: $(2(k+1), n(k+1), 2n(k+1))$ for $n = 8k+5$
- **$n \\equiv 5 \\pmod{12}$**: $(3t+2, (t+1)n, (3t+2)(t+1)n)$ for $n = 12t+5$

Re-verify these via \`lean_define\` first so they're in scope, then attack the residual.

## What's already been tried — DO NOT REPRODUCE

The Erdős–Straus literature is massive. Below is what you should NOT spend turns reinventing:

### Confirmed dead ends

1. **Brute-force computation.** Verified up to $n \\leq 10^{17}$ (Obláth 1948 → Salez 2014). Won't prove the conjecture.

2. **Polynomial identities for $n \\equiv 1 \\pmod p$, any degree.** **Mordell (1967)** proved this is impossible: identities require the residue $r$ to be a *non*-quadratic-residue mod $p$. Since $1$ is a QR for every $p$, no polynomial identity covers $n \\equiv 1 \\pmod p$ uniformly. **Don't attempt linear, quadratic, or higher polynomial parameterizations** — Mordell's quadratic-residue obstruction rules them all out.

3. **Greedy algorithm.** Produces four-term expansions for $n \\equiv 1 \\pmod 4$, not three-term. Useless here.

4. **Complete modular covering systems.** Mathematically impossible by Mordell's result combined with the requirement to cover $n \\equiv 1$ mod every prime.

5. **Linear-in-$n$ parameterization for $n \\equiv 1 \\pmod{24}$.** Already verified (Z3 UNSAT, special case of Mordell). Don't redo.

### Significant partial results (already published)

6. **Webb, Vaughan, Li, Yang, Ahmadi-Bleicher, Elsholtz.** Extended modular identities pushed the natural density of potential counterexamples toward zero.

7. **Heath-Brown (1996).** Density of failures is $O((\\log N)^{-3})$.

8. **Elsholtz & Tao (2013).** Average solution count bounded polylogarithmically.

9. **Bright & Loughran (2020).** *No Manin obstruction* exists — local solvability is fine; the obstruction (if any) is *deeper* than Brauer-Manin. **This is interesting:** rules out one whole class of obstructions.

10. **Ionascu & Wilson (2011).** Quadratic residue strategy: find prime $q$ where $p$ is non-residue mod $q$, then solve for $n \\equiv p \\pmod q$. Theoretical, implementation incomplete.

11. **Recent preprints (2025-2026).** ArXiv 2511.07465 (Nov 2025) claims constructive proof for ALL primes $P \\equiv 1 \\pmod 4$ via methods ED1 (factorization $(γA-c)(γB-c) = c^2$) and ED2 (linear system $(4b-1)(4c-1) = 4Pδ+1$). UNVERIFIED by peer review; treat with appropriate skepticism. ArXiv 2602.20036v2 proves density-1 result via the trick "$n$ has a prime factor $\\equiv 3 \\pmod 4$" (the Hasse-style sub-residue lift).

### What's known to NOT crack it

- Any covering-system approach (Mordell)
- Any pure greedy / extended-greedy approach
- Any approach via polynomial identities of bounded degree (Mordell, generalized)
- Brute force at human-feasible scale (already to $10^{17}$)
- Brauer-Manin obstruction analysis (Bright-Loughran: no obstruction)

## What might genuinely be under-explored

These are the angles where actual mathematical progress could plausibly come (in approximate order of how Lean-formalisable they are):

### A. Re-verify the recent preprint claims (ED1/ED2 from arXiv 2511.07465)

The ED2 method claims: for every prime $P \\equiv 1 \\pmod 4$, $(4b-1)(4c-1) = 4Pδ+1$ has integer solutions $(δ, b, c)$ giving an Erdős–Straus decomposition. **This is unverified by peer review.** A formal Lean/Z3 verification of the ED2 identity for specific primes (5, 13, 17, 29, 37, 41, ...) would be a real contribution — either confirming or finding a counterexample.

### B. Algebraic geometry on the variety

The equation defines an affine surface $V_n \\subset \\mathbb{A}^3_{\\mathbb{Q}}$. Bright-Loughran 2020 ruled out Manin obstructions. Open question: does $V_n$ have *integer* points beyond the known constructions for $n \\equiv 1 \\pmod 4$? Use Mathlib's algebraic-geometry primitives (\`AlgebraicGeometry.Scheme\`, \`Polynomial.IsAlgClosed\`, etc.) to formalize the variety and analyze its rational/integer point structure.

### C. Mathlib's \`MvPolynomial.combinatorial_nullstellensatz_exists_eval_nonzero\`

A previous run found this lemma in Mathlib. Combinatorial Nullstellensatz (Alon 1999) lets you prove existence of points where a polynomial doesn't vanish. The bridge: re-cast the Erdős–Straus existence as a non-vanishing question over a finite grid in $\\mathbb{Z}/n\\mathbb{Z}$, apply Nullstellensatz. This is technically hard but novel; nobody's bridged it for Erdős–Straus.

### D. Hasse-style prime-factor reduction

ArXiv 2602.20036 reduces composite $n \\equiv 1 \\pmod 4$ via prime-factor analysis: if $n$ has any prime factor $\\equiv 3 \\pmod 4$, the conjecture follows from the $n \\equiv 3 \\pmod 4$ case via division. The remaining cases are products of primes all $\\equiv 1 \\pmod 4$. **Formalize this reduction in Lean** — it would meaningfully cover most $n \\equiv 1 \\pmod 4$ and reduce the open case to "$n$ is a product of primes $\\equiv 1 \\pmod 4$."

### E. Quadratic-residue lift à la Ionascu-Wilson (2011)

For each prime $p \\equiv 1 \\pmod 4$, find a *different* prime $q$ such that $p$ is a non-quadratic-residue mod $q$. Then Mordell's identity for $n \\equiv p \\pmod q$ applies. This was theoretical in 2011; could be made constructive with computer search per $p$.

### F. Verify or refute specific small primes

For each prime $p \\equiv 1 \\pmod 4$ up to (say) $p = 1000$, find an explicit Erdős–Straus decomposition via SMT search. Confirm with verify_template-style cross-encoding. This gives an empirical baseline and may reveal patterns.

## Process

1. **Re-verify** the prior 5 residue-class theorems via \`lean_define\` (so the harness has them in your branch).
2. **Pick ONE under-explored angle** (A through F above). Don't try multiple at once.
3. **For each step**, ask: is this a known result I'd be reproducing? If so, skip. The literature catalog above is your guide.
4. **Formalize what you can** in Lean. Use \`lean_search\` aggressively to find Mathlib's existing primitives.
5. **Verify** with cross-encoding via \`verify_template\` / \`audit\` / \`review\`.
6. **Ship honestly**: the done-gate requires your final answer to substantively match verified artifacts.

## Realistic outcomes

- **Most likely**: you formally verify ED2's identity for specific primes $P \\equiv 1 \\pmod 4$ (5, 13, 17, ...), giving empirical confirmation of the unverified 2025 preprint.
- **Possible**: you formalize the Hasse-style prime-factor reduction (angle D), reducing $n \\equiv 1 \\pmod 4$ to "products of primes $\\equiv 1 \\pmod 4$" — a meaningful Lean contribution.
- **Significant**: you implement one of Ionascu-Wilson's quadratic-residue lifts in verifiable form for a specific prime class.
- **Vanishingly unlikely**: a new construction not in the literature.

## Critical reminders

- **Don't reproduce Mordell.** Polynomial identities for $n \\equiv 1 \\pmod p$ are dead. Linear, quadratic, any degree — all blocked by the QR obstruction.
- **Don't reproduce sub-residue mod-840 decomposition.** Already done.
- **Don't reproduce greedy.** Doesn't work for this class.
- **Do consult the catalog above** before each new angle.

## Budget: 100 turns`,
    expectedAnswer:
      "Open. The n ≡ 1 mod 4 case of Erdős–Straus remains the historically hardest residue class. Realistic measure of success: any new artifact that is NOT a special case of Mordell 1967, NOT a sub-residue rehash, and NOT a brute-force computation. Examples: formalizing the recent ED2 method for specific primes, formalizing Hasse-style prime-factor reduction (angle D), or applying Combinatorial Nullstellensatz (angle C).",
    maxSteps: 100,
  },

  "erdos-straus-mod1-formal-transfer": {
    id: "erdos-straus-mod1-formal-transfer",
    type: "OPEN PROBLEM — Erdős–Straus for n ≡ 1 mod 4 (cross-disciplinary FORMAL technique transfer)",
    difficulty: "very-hard",
    prompt: `## Starting point — RESUMING from prior verified results

Prior runs of this harness produced **Lean 4 + Mathlib proofs** for the Erdős–Straus conjecture in three of four residue classes mod 4. **You are resuming from these verified results** — re-verify them as your first \`lean_define\` calls so they are in scope, then build on them.

### Verified prior results (Lean 4 + Mathlib)

The shared \`Solution n\` structure used throughout:

\`\`\`lean
import Mathlib

structure Solution (n : ℕ) : Type where
  (x y z : ℕ)
  (hx : x ≠ 0) (hy : y ≠ 0) (hz : z ≠ 0)
  (h : 4 * x * y * z = n * (x * y + x * z + y * z))
\`\`\`

**Residue class $n \\equiv 0 \\pmod 2$** (even): $(k, 2k, 2k)$ for $n = 2k$.

\`\`\`lean
theorem erdos_even (k : ℕ) (hk : k ≠ 0) : Solution (2 * k) :=
  { x := k, y := 2 * k, z := 2 * k,
    hx := hk, hy := mul_ne_zero two_ne_zero hk, hz := mul_ne_zero two_ne_zero hk,
    h := by ring }
\`\`\`

**Residue class $n \\equiv 0 \\pmod 4$** refined: $(3m, 3m, 3m)$ for $n = 4m$.

**Residue class $n \\equiv 3 \\pmod 4$**: $(k+1, n(k+1)+1, n(k+1)(n(k+1)+1))$ for $n = 4k+3$.

\`\`\`lean
theorem erdos_mod4_3 (k : ℕ) :
    let n := 4 * k + 3
    Solution n :=
  let n := 4 * k + 3
  { x := k + 1,
    y := n * (k + 1) + 1,
    z := (n * (k + 1)) * (n * (k + 1) + 1),
    hx := by omega, hy := by omega,
    hz := mul_ne_zero (by positivity) (by omega),
    h := by ring }
\`\`\`

**Sub-residues of $n \\equiv 1 \\pmod 4$ already covered** (from a follow-up run):

- $n \\equiv 5 \\pmod{12}$: $(3t+2,\\, (t+1)(12t+5),\\, (3t+2)(t+1)(12t+5))$ for $n = 12t+5$.
- $n \\equiv 5 \\pmod 8$: $(2(k+1),\\, n(k+1),\\, 2n(k+1))$ for $n = 8k+5$.

These are Mordell/Webb/Schinzel-style identities exploiting that $x = (n+3)/4$ collapses the unit-fraction sum. **Standard sub-residue decomposition has been EXHAUSTED** by these constructions plus published mod-840 results — the residual sub-residues within $n \\equiv 1 \\pmod 4$ (specifically $n \\equiv 1 \\pmod{24}$ in part) is exactly what the standard trick stops working on.

### The genuinely open territory

After the above, what remains open within $n \\equiv 1 \\pmod 4$ is approximately the sub-residues where $(n + 3)/4$-style $x$ doesn't yield integer $y, z$ — concretely, $n \\equiv 1 \\pmod{24}$ and a few related sparse classes. These are the historically-hardest ones; they're why the conjecture is open after 78 years.

**Re-verify the prior results as your first move** (so the harness records them in your branch), then attack the residual.

## The actual goal

**Combine known formal techniques from different disciplines in a novel way.**

Every step of your proof must remain rigorously formal — Lean-verifiable, axiomatically sound, no hand-waving. The creativity is in the **choice of which formal technique to import** from a discipline that hasn't been applied to this problem before.

This is concrete, not speculative. Examples of what this kind of cross-disciplinary formal transfer looks like in real mathematical history:

- **Croot–Lev–Pach (2016)**: imported the **polynomial method** from coding theory + combinatorial design theory to crack the cap set conjecture. Each step formal; the novelty was applying degree-bounded polynomial arguments to a problem nobody had tried them on.
- **Marton/Tao/Green/Manners (2023)**: resolved the Polynomial Freiman-Ruzsa conjecture using **entropy methods from information theory** applied to additive combinatorics. Every step rigorous; the technique transfer was the breakthrough.
- **Wiles (1995)**: imported **modular forms and Galois representations** into the FLT problem, building on Frey-Serre-Ribet. Each piece formal; the novelty was the combination.
- **Helfgott (2013)**: resolved Goldbach's weak conjecture via **circle method + computational components**. Standard techniques, novel combination + scale.

In every case, the "creativity" was identifying a formal technique from another field whose abstract structure happens to apply. The verification machinery was an enabler, not an obstacle.

## What this means for our task

Pick a formal technique from a discipline whose application to Erdős–Straus is **non-obvious**. Concrete candidates (each is a real, formal, published technique):

### Algebraic geometry / commutative algebra
- The Erdős–Straus equation defines a variety $V \\subset \\mathbb{A}^3$ over $\\mathbb{Q}$. Use **Hilbert's Nullstellensatz** or **Gröbner basis computation** to characterise its rational/integer point structure for $n \\equiv 1 \\pmod 4$.
- Apply **scheme-theoretic descent** or **étale cohomology** to obstruct integer points (in the spirit of Manin obstructions for diophantine equations).

### Combinatorial / additive combinatorics
- **Combinatorial Nullstellensatz** (Alon 1999): if a multilinear polynomial vanishes on a grid, certain combinatorial structure follows. Applicable here?
- **Polynomial method à la Croot–Lev–Pach**: degree-bounded polynomial reasoning on the unit-fraction structure.
- **Plünnecke–Ruzsa inequalities** on the additive structure of the failure set.

### Information theory / probability
- **Entropy methods** (the PFR breakthrough): bound the entropy of decomposition distributions to force structural constraints.
- **Lovász Local Lemma**: model failures as bad events on a graph; show they don't all coexist.

### Number theory beyond elementary
- **L-function / character sum estimates**: failures of Erdős–Straus correlate with sign patterns of Dirichlet characters; use multiplicative number theory's analytic toolkit.
- **Modular form / theta function** identity: $4/n - (1/x + 1/y + 1/z)$ might admit a theta-series representation whose vanishing yields constructions.

### Computer science / algorithm theory
- **Sum-of-squares (SOS) hierarchy** from semidefinite programming: bound polynomial nonnegativity rigorously.
- **Algorithmic information theory**: failures must have low Kolmogorov complexity (bounded by their description); turn this into a counting argument.
- **Streaming-algorithm sketching arguments**: a formal way to summarise residue structure.

### Mathematical logic
- **Reverse mathematics**: identify the proof-theoretic strength of Erdős–Straus and reduce to a known classifiable principle.
- **Model-theoretic transfer**: prove for a non-standard model and transfer back via compactness/Łoś.

## Process

1. **Pick a technique.** ONE specific formal method from a non-obvious discipline. State it precisely.
2. **Articulate the transfer.** In your prose, spell out concretely how the technique's abstract structure maps onto the Erdős–Straus problem. The bridge is rigorous — every term in the technique should have a counterpart in the problem.
3. **Identify the load-bearing lemma.** What's the smallest formal claim that, if proven via your imported technique, would close the conjecture (or a meaningful subset of $n \\equiv 1 \\pmod 4$)?
4. **Formalize the setup in Lean.** Use \`lean_define\` to introduce the imported technique's primitives (as definitions or axioms, citing the source). Mathlib may have some of them; you'll need to add the rest.
5. **Prove the load-bearing lemma.** Stepwise via \`proof_start\` + \`proof_step\`, with the imported technique's machinery in scope.
6. **Verify, audit, ship.** The done-gate requires your final answer to substantively reference verified artifacts. Don't ship more than you proved.

## Critical reframings

**This is not speculation.** Every step is formal. The "novelty" is the technique-import choice, not in the proof structure. If your imported technique requires lemmas Mathlib doesn't have, **state them as axioms with explicit sources** (e.g., \`axiom CrootLevPach : ...\` with a comment citing the paper). The harness accepts axioms; it just records them in the artifact.

**Don't retreat to standard techniques after the first failed attempt.** That's what happened last time. If your first technique transfer doesn't yield a proof, **try a second technique transfer from a different discipline**, not a fallback to mod-24 decomposition.

**Mathlib has more than you think.** Search via \`lean_search\` for terms from your chosen discipline. The polynomial method, Nullstellensatz, entropy bounds, character sums, scheme theory — much is in Mathlib.

## Realistic outcomes

- **Most likely**: you formalize a cross-disciplinary technique, apply it to Erdős–Straus, and either prove a partial result that's genuinely new (because the technique-import is novel) OR honestly find that the technique doesn't apply and document why.
- **Possible**: a sub-residue class of $n \\equiv 1 \\pmod 4$ falls to your novel formal-transfer approach.
- **Vanishingly unlikely but real**: the full case yields. Would be a publishable result.
- **Equally valid**: the imported technique doesn't work, but the formalization of "what would it take to apply technique X to Erdős–Straus" is itself a research artifact.

## Budget: 100 turns

Spend turns on the technique-import argument and the bridge-construction. If you find yourself reaching for sub-residue tricks again, stop and re-read the technique catalog above.`,
    expectedAnswer:
      "Open. The n ≡ 1 mod 4 case of Erdős–Straus remains the hardest residue class. Realistic measure of success: any verified artifact produced by importing a formal technique from a non-obvious discipline (algebraic geometry, polynomial method, entropy, SOS, Nullstellensatz, etc.) — partial coverage of the open class is meaningful; the technique transfer itself is the contribution.",
    maxSteps: 100,
  },

  "erdos-straus-mod1-creative": {
    id: "erdos-straus-mod1-creative",
    type: "OPEN PROBLEM — Erdős–Straus for n ≡ 1 mod 4 (cross-disciplinary attempt)",
    difficulty: "very-hard",
    prompt: `## The remaining case

A previous run of this harness produced **Lean 4 + Mathlib proofs of the Erdős–Straus conjecture for $n \\equiv 0, 2, 3 \\pmod 4$** (commit a4c19fd). The constructions:

- $n \\equiv 0 \\pmod 2$: $(x, y, z) = (k, 2k, 2k)$ for $n = 2k$
- $n \\equiv 0 \\pmod 4$: $(x, y, z) = (3m, 3m, 3m)$ for $n = 4m$ (refined)
- $n \\equiv 3 \\pmod 4$: $(x, y, z) = \\bigl(k+1,\\; n(k+1)+1,\\; n(k+1)(n(k+1)+1)\\bigr)$ for $n = 4k+3$

**Your target: prove the conjecture for $n \\equiv 1 \\pmod 4$.** This single residue class is the entire remaining content of Erdős–Straus. It's been the bottleneck for 78 years.

## What's already been tried (don't repeat)

Mathematicians have spent decades on this. The standard approaches all fail at the residual sparse set:

- **Sub-residue decomposition** mod 24, mod 840: handles many $n \\equiv 1 \\pmod 4$ cases, leaves a residual.
- **Reduction to "$n$ has a prime factor $p$ with [certain property]"**: works for many primes, leaves the others.
- **Mordell's identity** and variations: covers more cases, still leaves a residual.
- **Polynomial identities in auxiliary parameters**: extensive search has found many, none cover the residual.
- **Computer search** to $n \\leq 10^{17}$: confirms the conjecture but proves nothing.
- **Heath-Brown density bound** (1996): density of $n$ violating the conjecture is $O((\\log N)^{-3})$. Doesn't cover the residual deterministically.

Standard techniques have been **exhausted** on this case. If a clean proof exists, it's almost certainly **not** another sub-residue decomposition or polynomial identity.

## Your charge: be creative across disciplines

This is the entire point of this run. **Don't try a standard number-theoretic approach.** Bring a perspective from a completely different field. Some jumping-off points — pick one that resonates, or invent your own:

### Physics
- **Statistical mechanics on integers**: treat $4/n = 1/x + 1/y + 1/z$ as an energy minimisation over a state space of triples $(x, y, z)$. Are there phase transitions in the density of solutions as $n$ varies through residues?
- **Spectral theory**: the operator $T(n) = \\inf_{(x,y,z)} \\|4/n - (1/x + 1/y + 1/z)\\|$ has structure as $n$ varies. What does its spectrum look like?
- **Quantum decomposition**: model unit fractions as states in a Hilbert space, the conjecture as a statement about reachability.

### Computer science / algorithms
- **Randomised algorithms / Lovász local lemma**: model the failure of the conjecture as a bad event, show its probability is < 1.
- **Kolmogorov complexity**: failures of Erdős-Straus would have low complexity descriptions; bound the complexity to bound failures.
- **Algorithmic information theory**: a counterexample would be a compressible structure; impossibility from incompressibility arguments.
- **Streaming / sketching**: what's the minimal "sketch" of $n$ that determines whether the conjecture holds?

### Information theory
- **Entropy bounds**: each $n$ admits multiple $(x, y, z)$ decompositions. What's the entropy of the decomposition distribution? Does it diverge for the open class?
- **Channel capacity**: model the conjecture as a coding problem; failures correspond to transmission errors with some bounded rate.

### Topology / algebraic geometry
- **The variety**: $4xyz - n(yz + xz + xy) = 0$ defines a surface in $\\mathbb{P}^3$ or $\\mathbb{A}^3$. Are there topological invariants (Euler characteristic, Betti numbers) that prevent integer points for specific residues?
- **Sheaf cohomology**: rational points on the variety as a cohomological obstruction.

### Game theory / dynamics
- **A two-player game**: the proposer picks $n \\equiv 1 \\pmod 4$, the responder must produce $(x, y, z)$. Is there a winning strategy for the responder regardless of the proposer's choice?
- **Dynamical systems**: iterate a map $n \\mapsto T(n)$ that's invariant on the failure set. If the failure set is empty, the map has no orbits — a topological constraint.

### Logic / proof theory
- **Reverse mathematics**: what's the proof-theoretic strength of Erdős–Straus? Is it equivalent to some known principle?
- **Reduction to a decidable fragment**: identify a fragment where this is decidable; reduce.

### Cross-pollination
- **Recent breakthroughs**: the Polynomial Freiman-Ruzsa conjecture was resolved in 2023 by Marton/Tao/Green/Manners using entropy methods after decades of failed attempts. Could a similar entropy-on-additive-structure approach apply here?
- **Cap sets**: Croot-Lev-Pach (2016) used polynomial-degree arguments to crack the cap set conjecture asymptotically. Is there an analogous polynomial-method approach for unit fractions?

## Tooling & verification

The harness's verification engines stay the same — anything you produce gets checked:

- **\`lean_define\`** to add the previous theorems' statements + your auxiliary types/axioms incrementally
- **\`proof_start\`** + **\`proof_step\`** for stepwise development
- **\`verify_lean\`** for one-shot proofs
- **\`verify_smt\`** for witness-finding at specific $n \\equiv 1 \\pmod 4$ (Z3 can find $(x, y, z)$ for any specific $n$ in the open class — that's not the question; the question is the GENERAL proof)
- **\`audit\`** to LLM-cross-check any structural argument before shipping
- **\`review\`** for explicit cross-checking with an independent encoding

## Process

1. **Choose your discipline.** Pick the angle that feels most promising or most underexplored. Commit to one — don't try five at 20% depth.
2. **Articulate the bridge.** In your prose, spell out concretely how the cross-disciplinary perspective maps onto the conjecture's structure. The bridge is the load-bearing piece.
3. **Identify the load-bearing lemma.** What's the smallest claim that, if proven via your novel angle, would close the conjecture (or a meaningful subset of $n \\equiv 1 \\pmod 4$)?
4. **Formalize what you can.** Use \`lean_define\` to set up the cross-disciplinary framing in Lean (or as much as fits).
5. **Verify, audit, ship.** Even if the full conjecture eludes you, a verified novel structural insight is meaningful.

## Realistic outcomes

- **Most likely**: you reach for a cross-disciplinary angle, formalize the framing, and prove a smaller structural result that captures part of the open case. This is genuinely new and meaningful.
- **Possible**: a sub-residue class of $n \\equiv 1 \\pmod 4$ falls to your novel approach.
- **Vanishingly unlikely but real**: the full $n \\equiv 1 \\pmod 4$ case yields. This would be a publishable contribution.
- **Equally valid**: you try a novel angle, it doesn't work, you ship the honest finding "approach X reduces the problem to claim Y, which I couldn't close." Negative results from creative attempts are also data.

## Critical reminder

**Resist the urge to retreat to standard techniques after the first failed creative attempt.** That's the failure mode that keeps this conjecture open. The standard techniques don't work — that's why we're trying something else. If your first cross-disciplinary angle doesn't pan out, **try a different cross-disciplinary angle**, not a standard fallback.

The harness will verify whatever you produce. Be bold.

**Budget: 100 turns.** Spend turns on the bridging argument, not on routine elaboration.`,
    expectedAnswer:
      "Open. The n ≡ 1 mod 4 case of Erdős–Straus is the historically-hardest residue class. Realistic measure of success: any verified novel structural argument, even if partial. Full proof not expected.",
    maxSteps: 100,
  },

  "erdos-straus-general": {
    id: "erdos-straus-general",
    type: "OPEN PROBLEM — Erdős–Straus conjecture, general proof attempt",
    difficulty: "very-hard",
    prompt: `## The conjecture

**Erdős–Straus (1948).** For every integer $n \\geq 2$, there exist positive integers $x, y, z$ such that
$$
\\frac{4}{n} = \\frac{1}{x} + \\frac{1}{y} + \\frac{1}{z}.
$$

**Status as of 2026**: open. Computationally verified for all $n \\leq 10^{17}$ (Salez 2014 and successors). No general proof published in 78 years.

## Your task

**Find a general proof.** Or, if a full proof eludes you, prove it for a residue class that's still listed as open in the literature, OR develop a novel structural argument that reduces the open cases. The realistic outcome is partial progress on a famous open problem; even that is a contribution.

This is not a benchmark — it's a research attempt. The harness's verification engines exist to check specific intermediate steps (residue-class proofs, identity verifications, witness searches). The creative part — picking the angle, choosing the abstraction, making unusual connections — is on you.

## What's already been tried (so you don't repeat)

Pre-existing partial results, all in the literature:

- **Residue classes of $n$ mod 840**: most have explicit constructions. E.g., $n \\equiv 0 \\pmod 4$ admits $4/n = 1/(n/4) + 1/k + 1/(-k)$ trivially with appropriate algebra. Many other classes have published Erdős/Straus/Schinzel constructions.
- **Probabilistic / heuristic arguments**: Heath-Brown 1996 showed the proportion of $n \\leq N$ where the conjecture holds is $1 - O((\\log N)^{-3})$. So almost all $n$ admit decompositions; the conjecture is for the residual sparse set.
- **Continued fractions / Stern-Brocot tree**: structural representations of unit fraction sums.
- **Mordell's identity** and its variations for specific residue classes.
- **Computer search**: exhaustive verification up to $10^{17}$.

The remaining open cases tend to involve specific congruence conditions on $n$ modulo small primes. Pick one and try.

## Be creative — this is the whole point

The conjecture has resisted classical attacks for 78 years. If a clean proof exists, it's likely from a **non-obvious angle**. Stretch your knowledge:

- **Algebraic number theory**: think of $4/n$ as an element of $\\mathbb{Q}$ and try $K$-theoretic / class-group arguments.
- **Algebraic geometry**: the condition $4/n = 1/x + 1/y + 1/z$ defines a variety over $\\mathbb{Q}$. Are there rational/integer point arguments from elliptic curves, modular forms, or Manin-style descent?
- **Combinatorics on words**: the partial fractions have a Stern-Brocot tree structure — is there a coding-theoretic angle?
- **Spectral / analytic methods**: $L$-functions, Hardy-Littlewood circle method, or character sums for specific residues.
- **Probabilistic number theory**: extending Heath-Brown's density bound to a full proof for the residual set.
- **Logical / proof-theoretic**: is there a reduction to a decidable fragment for specific classes?
- **Computer algebra / symbolic computation**: a polynomial identity that proves the conjecture by exhibiting parametrised solutions for an infinite family.
- **Cross-field surprises**: anything from physics (statistical mechanics on the integers?), CS (algorithm-design viewpoint?), or a recently-resolved problem in additive combinatorics that might transfer.

Don't restrict yourself to "standard" Erdős–Straus literature. The conjecture has been picked at by experts using standard techniques for decades — that's exactly why it's open. **The likeliest path to progress is a connection nobody's tried.**

## Tooling

- **\`verify_lean\`** / **\`proof_start\`** for any structural proof step. Use this for residue-class proofs, polynomial identity proofs, anything Lean can elaborate.
- **\`lean_define\`** to build up a development with the conjecture's definitions plus your auxiliary lemmas.
- **\`lean_search\`** to find Mathlib's lemmas on \`Nat.gcd\`, \`Nat.div\`, modular arithmetic, partial fractions, etc.
- **\`verify_smt\`** for witness-finding at specific $n$. Z3 can find $(x, y, z)$ for moderate $n$ in seconds.
- **\`audit\`** if you produce a confirmed artifact and want a sub-LLM review for soundness before shipping.

## Process

1. **Survey** in your prose: what techniques have been tried, where have they hit a wall, and which under-explored angle would you bring?
2. **Pick an angle** and commit. Don't try five things at 20% depth; try one at full depth.
3. **Formalize the setup** in Lean via \`lean_define\` — at minimum, the conjecture statement.
4. **Attack the load-bearing lemma.** What's the smallest claim that, if proven, implies the conjecture (or a meaningful subset)?
5. **Verify what you can**, ship what you have. The done-gate requires your final answer to substantively match your verified artifacts.

## Realistic outcomes

- **Most likely**: formalize the conjecture in Lean, verify it for many small $n$ via SMT, prove for a residue class that has a known construction.
- **Possible**: prove a residue class that's currently listed as open, or formalise a partial result that hasn't been Lean-verified before.
- **Vanishingly unlikely**: full proof. We're not expecting to crack a 78-year-old conjecture in 80 turns. **But the goal is to TRY, with creativity.**

## Budget: 80 turns

Use them for genuine exploration. Don't rush to verify trivial cases — spend turns on the angle-of-attack discussion, then dig in.`,
    expectedAnswer:
      "Open. The Erdős–Straus conjecture (4/n = 1/x + 1/y + 1/z for all n ≥ 2) is unsolved in general. Realistic measure of success: any verified artifact that closes a residue class currently listed as open, or a structural lemma reducing the open cases. Full proof is not expected.",
    maxSteps: 80,
  },

  "schur-coloring-frontier": {
    id: "schur-coloring-frontier",
    type: "OPEN-FRONTIER PROBLEM — Schur number S(5) and beyond",
    difficulty: "very-hard",
    prompt: `## The problem

A *Schur k-coloring* of $[1, n]$ is a function $c : \\{1, \\ldots, n\\} \\to \\{1, \\ldots, k\\}$ such that there is no monochromatic Schur triple — no $x, y, z \\in [1, n]$ with $x + y = z$ and $c(x) = c(y) = c(z)$.

The Schur number $S(k)$ is the largest $n$ for which a Schur $k$-coloring of $[1, n]$ exists. Known values:

- $S(2) = 4$ (Schur 1916)
- $S(3) = 13$ (Schur 1916)
- $S(4) = 44$ (Baumert 1965, computer-assisted)
- $S(5) = 160$ (Heule 2017, **massive SAT instance, 4 trillion clauses**)
- $S(6)$ is **OPEN**: best known $S(6) \\geq 537$.

## Starting point — RESUMING from a prior verified result

A previous run of this harness produced a **verified 4-coloring of $[1, 40]$** (both Z3 and JS-enumeration cross-check agreed):

\`\`\`
[1, 2, 2, 1, 3, 3, 3, 3, 3, 1, 2, 2, 1,
 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
 1, 2, 2, 1, 3, 3, 3, 3, 3, 1, 2, 2, 1]
\`\`\`

(positions 1..40 in row-major order). Construction: a recursive Schur lift of the 3-coloring $[1,2,2,1,3,3,3,3,3,1,2,2,1]$ of $[1, 13]$, padding positions 14-27 with color 4 and recursing.

**Resume by re-verifying this coloring as your first call** (so you can build on it), then attempt to extend to $[1, 44]$ (Goal A) by finding $c_{41}, c_{42}, c_{43}, c_{44}$ that preserve Schur-goodness. The previous run tried 17 different 4-colorings of $[1, 44]$ and all were refuted — so this is genuinely the hard part. Use **lean on knowledge from the literature** (Baumert 1965 used computer search; the structure is known to be irregular near the boundary).

## Your goals (in order of difficulty)

### Goal A (the resume target): exhibit a 4-coloring of $[1, 44]$

Find $c : [1, 44] \\to \\{1, 2, 3, 4\\}$ with no monochromatic Schur triple. Verify with \`verify_template[schur_coloring]\`. **Strongly recommended**: extend the verified $[1, 40]$ above by finding 4 more positions, OR start fresh with a different known construction (e.g., Baumert's specific coloring from the literature). Settles $S(4) \\geq 44$.

### Goal B (the main attempt): exhibit a 5-coloring of $[1, 160]$

Find $c : [1, 160] \\to \\{1, 2, 3, 4, 5\\}$ with no monochromatic Schur triple. This was the Heule 2017 result; he produced an explicit coloring via SAT search. The model has read his paper and may know the structure (typical Schur colorings use **multiplicative** structure on a residue class). Settles $S(5) \\geq 160$ — well-known but a real demonstration of the harness on a near-frontier problem.

### Goal C (the genuinely open one): exhibit a 6-coloring of $[1, 538]$

This would push $S(6) \\geq 538$, beyond the published lower bound of 537. The exact value of $S(6)$ is open; if such a coloring exists, finding it would be a real contribution. Heule and others have searched extensively and not yet found 538 (or a refutation). The model is unlikely to find this — but exhibiting any large 6-coloring is informative.

## Tooling

Use \`verify_template\` with template \`"schur_coloring"\` and slots:

\`\`\`
{"name": "verify_template", "args": {
  "claim": "5-coloring of [1, 160] with no monochromatic Schur triple",
  "template": "schur_coloring",
  "slots": {
    "n": 160,
    "k": 5,
    "coloring": [1, 2, 3, 4, 5, 1, 2, 3, 4, 5, ...]
  }
}}
\`\`\`

The template runs both a Z3 existential check (UNSAT means no bad triple exists) and a JS-enumerated cross-check (all $\\binom{n+1}{2}$ pairs (x, y) with $x + y \\le n$). Both must agree.

## Process

1. **Range first.** Before submitting any candidate coloring, name 3-5 distinct construction families. Examples:
   - **Multiplicative on residues mod p**: assign color based on $\\lfloor i / m \\rfloor$ mod something
   - **Quadratic residues**: color by quadratic-residue class
   - **Recursive Sidon-like**: build the coloring by lifting a smaller Schur-good coloring
   - **SAT-derived**: cite Heule's explicit coloring (the model has seen it)
   - **Multiplicative character of cyclic group**: c(i) = (i mod q) for some prime q with the property
   For each, predict whether it scales to the target $n$.

2. **Goal A first.** Match the Baumert 4-coloring of $[1, 44]$. Should take 2-3 attempts.

3. **Goal B.** This is where it gets hard. The model knows Heule's paper exists; the explicit coloring is in the supplementary materials. If the model can reproduce or approximate it, the template will verify.

4. **Goal C.** A genuine attempt at the open frontier. Even producing a verified 6-coloring of [1, 537] (matching the lower bound) is meaningful.

5. **Report.** Final \`done\` answer must include: the colorings achieved, comparison to known bounds, and a clear statement of which goals were reached.

## What success looks like

- Goal A reached → harness demonstration on a known-hard combinatorial verification.
- Goal B reached → matches a 2017 record, real demonstration of LLM + SAT-checker on a near-frontier problem.
- Goal C — extremely unlikely but the attempt would be informative.

## Caveats

The Z3 verification scales as the coloring grows. For n=44 it's instant. For n=160 it could take 30s+ per call. For n=537+ it might time out. If templates time out, that's a finding too.

**Budget: 60 turns.** Spend them on creative construction selection. Don't expect to brute-force search via Z3 — Z3 is a checker, not a searcher for this class.`,
    expectedAnswer:
      "Open at the S(6) frontier. S(2)=4, S(3)=13, S(4)=44, S(5)=160 known. Grade by largest verified coloring: |c|≥44 (Goal A) is working; |c|≥160 (Goal B) matches Heule 2017; |c|≥538 with k=6 (Goal C) would push the open frontier. The exact S(6) is unknown.",
    maxSteps: 60,
  },

  "frankl-union-closed": {
    id: "frankl-union-closed",
    type: "OPEN CONJECTURE — Frankl's Union-Closed Sets (1979)",
    difficulty: "very-hard",
    prompt: `## The conjecture

**Frankl's Union-Closed Sets Conjecture (Frankl, 1979).** Let $F$ be a finite, non-empty family of finite sets, closed under union (i.e., $A, B \\in F \\implies A \\cup B \\in F$), and suppose $F$ contains at least one non-empty set. Then there exists an element $x$ belonging to at least half the sets in $F$:
$$
\\exists x \\in \\bigcup F \\quad \\text{such that} \\quad |\\{ S \\in F : x \\in S \\}| \\geq |F| / 2.
$$

This conjecture is **open**. The best known unconditional bound is due to Gilmer (2022), giving roughly $|\\{ S \\ni x \\}| \\geq 0.38 |F|$ via an entropy argument. The originally-conjectured constant 0.5 is still unproven.

## Your task: progressive Lean formalization

We are NOT asking you to prove the conjecture in full. We want a structured, formally-verified progression:

### Level 1 (mandatory): Formalize the statement

In Lean 4 + Mathlib, define:
- A predicate \`IsUnionClosed (F : Finset (Finset α)) : Prop\` capturing "closed under union and non-empty with some non-empty set."
- The proposition \`FranklConjecture (F : Finset (Finset α)) : Prop\` stating "$\\exists x$ in $\\bigcup F$ with $|\\{S \\in F : x \\in S\\}| \\geq |F| / 2$."

Use \`verify_lean\` to confirm the definitions compile against Mathlib.

### Level 2: Trivial small cases

Prove:
- **L2a**: For $F = \\{\\emptyset, \\{a\\}\\}$ (where $a$ is some element), the conjecture holds.
- **L2b**: For $|F| = 1$, the conjecture holds (any element of the single set belongs to all sets).
- **L2c**: For $|F| = 2$, the conjecture holds.

Each as a separate Lean theorem. These are sanity checks; they should not be hard.

### Level 3 (the meaningful goal): The 2-element-set lemma

**Lemma.** If $F$ is union-closed and contains a 2-element set $\\{a, b\\}$, then either $a$ or $b$ belongs to at least $|F|/2$ sets of $F$.

This is a published, well-known partial result. The proof is a counting argument: pair off the sets in $F$ that contain $a$ with those that don't (and similarly for $b$); union-closure forces a balance. Look up the canonical proof in the literature; the model is expected to know it.

Prove this in Lean. \`lean_search\` will help you find Mathlib lemmas about \`Finset.card\`, \`Finset.image\`, and pair injections.

### Level 4 (bonus, ambitious): Specific structural cases

Prove ANY of (in order of difficulty):
- If the smallest set in $F$ has size $\\leq 2$, the conjecture holds (subsumes L3 plus the singleton case).
- If $|F| \\leq 46$, the conjecture holds (computational; was verified by Lo Faro 1994 via case analysis).
- A formal statement of Gilmer's 0.38 bound, with as much of the entropy argument as you can encode.

These are MUCH harder; partial progress is fine. Don't attempt all of them — pick the one you can make most progress on and lean into it.

## Tooling

- \`proof_start\` + \`proof_step\` for stepwise Lean proofs (preferred for L2c, L3 — they involve case analysis or counting).
- \`verify_lean\` for one-shot proofs of L1, L2a, L2b.
- \`lean_search\` early and often. You'll need: \`Finset.card\`, \`Finset.image\`, \`Finset.union\`, \`Finset.filter\`, \`Nat.div_le_iff\`, \`Finset.sum_card_filter_attach\` and similar.

## Output expectations

A run that produces:
- L1 (formalized statement) ⇒ baseline success.
- L1 + L2 (statement + trivial cases) ⇒ solid demonstration of formalization capability.
- L1 + L2 + L3 (statement + trivial cases + 2-element-set lemma) ⇒ **target outcome**. This means we have a Lean-verified proof of a genuine published partial result for an open conjecture.
- L4 (any structural case) ⇒ stretch.

**Budget: 80 turns.** Spend turns on Lean elaboration; don't reach for SMT (this is a structural mathematical theorem, not a finite-instance check). Use \`proof_start\` aggressively for L3 — the counting argument has multiple steps and benefits from per-tactic feedback.

## Critical instructions

1. **Don't try to prove the full conjecture.** It's open. Focus on the levels above.
2. **Don't formalize from a blank slate.** Use Mathlib's \`Finset\` everywhere; don't redefine sets manually.
3. **State theorems precisely.** Each Lean theorem statement should match the natural-language statement we wrote above. Mismatches in quantifier scope or division convention (\`|F| / 2\` is integer division in Nat; you may need to use \`2 * |{S ∋ x}| ≥ |F|\` instead) are common pitfalls.
4. **Cross-check your L3 proof with \`review\`.** This is a mathematical proof, not a model-supplied SMT encoding, but the discipline of cross-checking still applies — sketch the proof informally before/after the formal version and compare.
5. **Call \`done\` when you have your highest-level achievement, with a precise summary of which levels you reached and which you didn't.**`,
    expectedAnswer:
      "A Lean 4 + Mathlib formalization with at least Level 1 (statement) and Level 2 (trivial cases) verified. Target is Level 3 (the 2-element-set lemma). Level 4 is a stretch. Frankl's full conjecture is open and not expected.",
    maxSteps: 80,
  },

  "rigging-no-equivocation": {
    id: "rigging-no-equivocation",
    type: "Cryptographic protocol theorem — hitch non-equivocation guarantee",
    difficulty: "very-hard",
    prompt: `**Prove the fundamental rigging guarantee** (TODA Rigging Specifications v0.9876, §6).

## Background

A *line* is a sequence of *twists* (each twist is a hash-identified data structure that succeeds the previous one). A line *equivocates* if a single twist has two distinct valid successors — i.e., the line forks. The cryptographic question of interest: under what construction can we guarantee that an untrusted line cannot equivocate?

A **hitch** is the fundamental unit of rigging. It connects two segments — a *footline* (untrusted) to a *topline* (trusted), via 5 distinguished twists:
- **fastener** — the first twist of the topline
- **lead** — the first twist of the footline; supplies a secret \`lead.shld\`
- **meet** — the last twist of the footline; the canonical successor of \`lead\`
- **hoist** — the twist on the topline that incorporates the lead-meet binding
- **post** — succeeds the hoist; carries the rigging trie containing the binding

**Shielding.** The footline operator keeps \`lead.shld\` secret. The shield function is
$$
S(\\text{hitch}, x) = H_{\\text{alg}(I(\\text{lead}))}\\bigl(C(\\text{lead.shld}) \\mid\\mid x\\bigr)
$$
where $H_a$ is the hash for algorithm $a$, $I(\\cdot)$ is the twist identifier, and $C(\\cdot)$ extracts content bytes.

**Hitch validity.** A valid hitch requires the hoist's rigging trie to contain BOTH:
1. \`hoist.rigs[S(hitch, I(lead))] = I(meet)\`
2. \`hoist.rigs[S(hitch, S(hitch, I(lead)))] = S(hitch, I(meet))\`

Plus a "no-collision proof" that no twist between fastener and hoist contains a conflicting pair under the same shielded keys.

## Theorem to prove

**Theorem (Hitch non-equivocation).** Assume:
- $H$ is a *collision-resistant cryptographic hash function* (treated as an injective oracle for proof purposes — no two distinct inputs map to the same output).
- Twist identifiers are determined by hashing twist contents, so distinct twists have distinct identifiers.
- The footline operator's secret \`lead.shld\` is unknown to any other party until disclosed.

If the **topline** (segment from fastener to hoist) has not equivocated — i.e., the topline has a unique sequence of twists from \`fastener\` to \`hoist\` — then the **footline** (segment from \`lead\` to \`meet\`) has not equivocated either: the meet identified by the hoist's rigging trie is the unique canonical successor of \`lead\` in the footline.

## Proof obligations

You don't need a single Lean script that compiles end-to-end (formalising the entire rigging protocol in Mathlib would take weeks). Instead, produce a **structured proof argument** with as much formalisation as is feasible:

1. **Setup**: state the abstract types (twists, identifiers, hashes) and the collision-resistance axiom in Lean (using \`opaque\` or \`axiom\`).

2. **Key lemmas**: prove or formally state:
   - **L1 (uniqueness of shielded key-value)**: given the secret \`lead.shld\`, only the footline operator can produce a valid second pair \`[S(S(lead)), S(meet)]\` matching a chosen \`[S(lead), meet]\`. Any forgery requires a hash collision.
   - **L2 (hoist uniqueness)**: any two valid hitches with the same lead and same hoist must have the same meet. This is the heart of the theorem.
   - **L3 (canonical succession)**: from L2, \`meet\` is the unique canonical successor of \`lead\` modulo the topline.

3. **Main theorem**: if the topline is unique (uses the L2 lemma), the meet is determined → the footline up to meet is unique.

## Tools

- \`verify_lean\` / \`proof_start\` / \`proof_step\` for the formal lemmas. Mathlib has injection-style lemmas (\`Function.Injective\`) you can leverage.
- \`lean_search\` to find Mathlib lemmas on injectivity, hash-like structures, or unique-existence.
- \`verify_smt\` for any small finite-instance sanity checks (e.g., a 2-twist hitch model).

## Output expectations

A valid attempt has:
- Definitions of the relevant abstract types in Lean (twist, hitch, hoist's rigging trie as a function/finmap)
- The collision-resistance axiom stated explicitly
- L1, L2, or L3 proven (any one is meaningful progress; all three is the goal)
- The main theorem stated formally (proof can defer to lemmas)
- A natural-language summary of why the proof works

This is a structural/cryptographic theorem, not a constructive combinatorial problem. Lean is the right tool. Don't try to verify with SMT-LIB existential queries — they'll time out. Prefer \`proof_start\` + tactic-by-tactic development.

**Budget: 50 turns.** Use them for genuine proof construction, not encoding fiddling. If you can't make the formalisation work in 5 turns, pivot to writing a precise informal proof with whichever lemmas you CAN formalise sprinkled in.

**What success looks like:** at minimum, a Lean snippet that compiles defining the abstract setup + the collision-resistance axiom. Better: at least one of L1/L2/L3 proven. Best: the main theorem stated and proven from the lemmas.`,
    expectedAnswer:
      "A structured proof of hitch non-equivocation. The core argument: shielded key-value pairs in the hoist's rigging trie cryptographically bind lead to meet via the footline operator's secret lead.shld; any equivocation in the footline (two distinct meets for the same lead) would require either a hash collision (violating the assumption) or an unauthorized party knowing lead.shld (also assumed impossible). Formal Lean grounding for the collision-resistance axiom and the bind-lemma is the meaningful deliverable; full formalisation of the protocol is out of scope.",
    maxSteps: 50,
  },

  "open-capset-f3-7": {
    id: "open-capset-f3-7",
    type: "GENUINELY OPEN PROBLEM — maximum cap set in F_3^7",
    difficulty: "very-hard",
    prompt: `**THIS IS A GENUINELY OPEN PROBLEM.** A cap set in $F_3^n$ is a subset $S \\subset F_3^n$ with no three distinct elements $x, y, z$ satisfying $x + y + z = 0$ in $F_3^n$ (component-wise modular sum). The maximum cap set size $r_3(F_3^n)$ is the central object of the cap-set conjecture.

**Status of $r_3(F_3^n)$:**
- $n = 1$: 2  (trivial)
- $n = 2$: 4  (corner)
- $n = 3$: 9  (Pellegrino 1971)
- $n = 4$: 20 (proved in the 1980s)
- $n = 5$: 45 (Edel et al. 2002)
- $n = 6$: 112 (Potechin 2008, exhaustive)
- $n = 7$: **OPEN.** Best known lower bound is around 236 (explicit constructions by Edel, Calderbank-Fishburn variants); best known upper bound is the Ellenberg-Gijswijt asymptotic specialised to $n=7$, giving roughly 290-300.

**Goal.** Construct an explicit cap set $S \\subset F_3^7$ of size as large as possible. The harness will verify it via the \`cap_set_f3n\` template (primary Z3 + independent enumeration cross-check; both must agree).

**Reward gradient.**
- **Trivial (~50-100):** any small construction. Easy.
- **150-200:** algebraic construction (e.g., projective lines in PG(6, 3) variant).
- **220-235:** known sub-frontier results from the literature.
- **236+:** matches the published lower bound.
- **240+:** at or beyond the literature frontier. Worth recording.
- **250+:** would push the lower bound. Major result.

**Tooling.**

Use \`verify_template\` with \`template: "cap_set_f3n"\` and slots:
\`\`\`
{
  "n": 7,
  "elements": [<base-3 integer encoding of each F_3^7 vector>]
}
\`\`\`

**Encoding convention:** an F_3^7 vector $v = (v_0, v_1, \\ldots, v_6)$ with each $v_i \\in \\{0, 1, 2\\}$ is encoded as the integer $V = v_0 + 3 v_1 + 9 v_2 + 27 v_3 + 81 v_4 + 243 v_5 + 729 v_6$, ranging $[0, 2186]$.

**Process.**

1. **Range first.** Before submitting any candidate, name 3-5 distinct construction families from different mathematical traditions:
   - **Algebraic / projective:** caps in PG(n-1, 3); Kuijken-van Maldeghem constructions.
   - **Coding theory:** ternary BCH codes, dual codes of small weight.
   - **Combinatorial / direct:** Pellegrino-style block constructions.
   - **Computational:** SAT-grown cap from a smaller seed cap.
   - **Hybrid:** lift a smaller cap (e.g., the 112-cap in $F_3^6$) and extend.
   For each, predict the size at $n=7$.

2. **Pick one. Construct.** Output the explicit subset as a list of base-3 integers in [0, 2186].

3. **Verify** with \`verify_template\`. The template runs both encodings; on PASSED, your result is robustly cross-verified.

4. **Iterate.** If verified at size N, can you grow to N+1? Try adding individual elements, re-verify. Each grow attempt is one verify_template call.

5. **Report.** Final \`done\` answer must include: chosen construction, the explicit set (or its generating description if compact), verified size, comparison to the published lower bound (~236) and upper bound (~290-300).

**Budget: 80 turns.** Cap-set verification is heavier than Sidon (more elements, more constraints) so expect each verify call to take a few seconds. Use the budget for genuine exploration of multiple constructions.

**What success looks like:** a verified cap set of size $\\geq 200$ matches solid published constructions; $\\geq 236$ matches the best-known lower bound; $\\geq 240$ would be at the frontier; $\\geq 250$ would be a real result. Even at smaller sizes, exhibiting a clean verified construction has demonstrative value.

**What failure looks like:** the model writes constructions that fail verification (either size collapses or the template refutes them). That's normal — cap sets are hard. Use \`give_up\` if the surviving branches plateau at a small size with no path forward.

We are not expecting to match 236 on first try. The exercise is: how large a cap set can we get with cross-checked verification, and does the system make any progress?`,
    expectedAnswer:
      "Open. The maximum cap set in F_3^7 is unknown. Published lower bound ≈ 236; upper bound ≈ 290. Grade by verified size: ≥ 100 = working; ≥ 200 = real; ≥ 236 = matches frontier; > 236 = potentially novel.",
    maxSteps: 80,
  },

  "open-3ap-free-300": {
    id: "open-3ap-free-300",
    type: "OPEN PROBLEM — large 3-AP-free subset of [1, 300]",
    difficulty: "very-hard",
    prompt: `**THIS IS AN OPEN PROBLEM.** The exact maximum size of a 3-AP-free subset of {1, 2, …, n} (denoted r_3(n)) is computed exactly in OEIS A003002 only for small n; for n in the few-hundred range, only bounds are recorded. The general behaviour is r_3(n) = n / (log n)^{Ω(√log log n)} (Behrend 1946; Elkin's 2010 improvement; Croot–Lev–Pach / Ellenberg–Gijswijt for the F_3^n cousin). This is NOT a problem with a known closed-form answer — your job is creative.

**Goal.** Construct a subset S ⊆ {1, 2, …, 300} of size as large as possible such that S contains NO three-term arithmetic progression (i.e., no a, d ≥ 1 with a, a+d, a+2d all in S; note d > 0, and a, a+d, a+2d distinct).

**Reward gradient.**
  - **Floor (~30):** the greedy / random construction. Easy. Not interesting.
  - **Behrend-like (~40–50):** the Behrend 1946 construction or its Elkin-style refinement at n=300. This is the published baseline. Match it and you've executed the literature correctly.
  - **Above 50:** plausibly novel. Genuinely worth writing down if verified.
  - **Above the Mathematica/SAT-derived bounds for this scale:** a publishable result.

**Tooling rules — read these before doing anything else.**

  - **Strongly preferred: use \`verify_template\` with template "no_3ap_subset".** The harness has a vetted template for this exact problem shape that runs BOTH a primary encoding (existence-of-3AP via Z3) AND an independent cross-check (explicit triple enumeration), and records the artifact as confirmed only if both encodings agree. Eliminates encoding bugs entirely. Use:
    \`\`\`
    {"name": "verify_template", "args": {
      "claim": "S = {...} is 3-AP-free in [1, 300]",
      "template": "no_3ap_subset",
      "slots": {"elements": [1, 2, 4, 5, ...]}
    }}
    \`\`\`
    On confirmation, no separate \`review\` is needed — the cross-check is built in.
  - **Fallback only**: \`verify_smt\` is available but you'll need to manually \`review\` with an independent encoding before \`done\`.
  - **Do NOT spend turns building Prolog generators with \`add_rule\` / \`verify\`** — Prolog is the wrong fit for this problem; compute candidate sets in your head and submit the explicit list of integers.
  - **You must call \`done\` at the end.** Pick your best verified set, summarise it, and call \`done\`.
  - **Budget: 60 turns.** Plenty for both range-first exploration AND a clean ship cycle.

**Process — this is the part we care about.**

You are NOT proving R(3,3); the answer is unknown. We want creative problem-solving.

  1. **Range first.** Before producing any candidate set, propose 3–5 *distinct constructions from different mathematical traditions*. Examples to spark thought (don't use these verbatim — pick your own):
     - Number-theoretic: Behrend's "high-dimensional sphere" lift via base-q digits with bounded digit-sum-of-squares.
     - Multiplicative-character: residues that avoid certain quadratic patterns.
     - Probabilistic / explicit pseudo-random: random sets conditioned on no 3-AP, then derandomised.
     - Algebraic: subsets of Z/pZ avoiding 3-APs lifted to [1, n].
     - Computational: SAT-driven construction starting from a Behrend skeleton, swapping elements to grow.
     - Geometric: lattice points on a sphere in Z^k, mapped to [1, n] via base-q.
     For each, write 2-3 sentences: what's the construction, why might it work at n=300, what's the predicted size?

  2. **Pick one. Commit.** Choose the most promising. Justify briefly.

  3. **Construct.** Output the explicit subset S as a sorted list of integers.

  4. **Verify.** Encode "S contains no 3-AP" in SMT-LIB and call \`verify_smt\`. The encoding is short:
     - declare S as a fixed list of integers (e.g., \`(define-fun S () (Array Int Bool) (store (store ... (const false) ...)))\` or simply enumerate membership predicates).
     - assert: \`(exists ((a Int) (d Int)) (and (> d 0) (in-S a) (in-S (+ a d)) (in-S (+ a (* 2 d)))))\`
     - expected: UNSAT (no 3-AP in S).
     A simpler encoding: declare elements as constants, assert distinctness, and assert NOT (any of the C(|S|, 3) candidate triples form an AP — there are only ~|S|² such pairs to check, manageable for |S| ≤ 60). Pick whichever encoding you can write cleanly.

  5. **Iterate.** If verification rejects S (some 3-AP slipped in), explain what you learned, repair or pivot, try again. Verification failure is data, not defeat.

  6. **Report.** Final answer must include:
     - The chosen construction (named, with citation if applicable).
     - The explicit subset S.
     - The verified |S|.
     - Comparison: is this above, at, or below the published Behrend bound for n=300? (Give a numerical estimate.)

**What success looks like.**
  - Produce a set S of size ≥ 40 with verified no-3-AP property.
  - The reasoning trace shows genuine cross-subfield exploration, not regurgitation.
  - The construction is explainable: a future reader can understand WHY this set has no 3-AP without re-running the verifier.

**What failure looks like (still useful!).**
  - A construction that the verifier rejects. (You learned something about the construction.)
  - A construction smaller than the trivial bound. (You learned something about your encoding.)
  - The model confidently proposing a "clever" construction that's actually 3-AP-rich. (Verification catches this — that's the whole point.)

You have lots of turns; don't rush. The interesting trace is one where you propose something, the verifier disagrees, and you iterate.`,
    expectedAnswer:
      "Open. Floor: ~30 from greedy. Behrend baseline at n=300 yields roughly 35–50 (depends on parameter choice). Anything ≥ 40 with template-verified no-3-AP is an honest result; ≥ 50 is plausibly novel. The harness should not declare a 'correct' answer here — judge by the *cross-checked size achieved*.",
    maxSteps: 60,
  },

  "open-sidon-set-500": {
    id: "open-sidon-set-500",
    type: "OPEN PROBLEM — large Sidon set in [1, 500]",
    difficulty: "very-hard",
    prompt: `**THIS IS AN OPEN PROBLEM (computational instance at the literature frontier).** A Sidon set (or B_2 set) in {1, …, n} is a subset S where all pairwise sums a+b (a ≤ b in S) are DISTINCT. Equivalently: all non-zero pairwise differences are distinct. The maximum size F_2(n) is known to be √n + O(n^{1/4}) (Erdős–Turán 1941; Lindström 1969), but exact values for n in the few-hundred-to-thousand range are not in standard tables. **For n = 500, the published Singer bound gives ≥ 23** (Singer's projective-plane construction with prime power q=23 yields q+1 = 24 elements in [0, q² + q] = [0, 552], with 23 of them landing in [0, 500]). Best-known constructions at this scale typically reach 23-24; reaching 25 would be at or near the literature frontier; matching/beating 24 is genuinely interesting territory.

**Goal.** Construct a Sidon set S ⊆ {1, 2, …, 500} of size as large as possible.

**Reward gradient.**
  - **Trivial (~15-18):** greedy / Mian-Chowla truncation. Easy.
  - **20-22:** scaled Erdős-Turán or partial Singer construction. Honest result.
  - **23:** match the published Singer bound for q=23 cropped to [1, 500]. Solid target.
  - **24+:** at or beyond the typical construction frontier at this n. Genuinely interesting.
  - **25+:** would beat known explicit constructions at this scale. Verify carefully — if the verifier accepts, this is a real result.

**Tooling rules — read FIRST.**

  - **Strongly preferred: use \`verify_template\` with template "sidon_set".** The harness has a vetted template for exactly this problem shape that runs BOTH encodings (distinct-sums + existence-of-collision) and records the artifact as confirmed only if both agree. Eliminates encoding bugs entirely — past runs lost results to forall-ordering-chain mistakes and missing-distinctness false positives. Use:
    \`\`\`
    {"name": "verify_template", "args": {
      "claim": "S = {...} is a Sidon set in [1, 500]",
      "template": "sidon_set",
      "slots": {"elements": [1, 2, 4, 8, 13, ...]}
    }}
    \`\`\`
    On confirmation, no separate \`review\` is needed — the cross-check is built in.
  - **Fallback only**: \`verify_smt\` is available for shapes the templates don't cover, but you'll need to manually \`review\` with an independent encoding before \`done\`.
  - **ALWAYS pass \`expectedVerdict\`** in your verify_smt args. The harness needs to know which Z3 verdict supports your claim. Without it, your call is logged as "ambiguous" and the user can't tell from the artifact whether your claim was confirmed.
  - **Recommended encoding (with explicit expectedVerdict, if you must use verify_smt):**
    \`\`\`
    {"name": "verify_smt", "args": {
      "claim": "S = {1, 2, 5, ...} is a Sidon set in [1, 500]",
      "smtlib": "(declare-const a1 Int) (assert (= a1 1)) ... (assert (distinct (+ a1 a2) (+ a1 a3) ... (+ a_{k-1} a_k)))",
      "expectedVerdict": "sat"
    }}
    \`\`\`
    Here SAT means "the (distinct) constraint is satisfiable with these fixed values" → S IS Sidon. UNSAT would mean the distinctness fails → S is NOT Sidon. So expectedVerdict is "sat".
  - **Alternative encoding** (existence-of-collision): assert that two distinct unordered pairs have the same sum; then UNSAT means no collision exists, i.e., S is Sidon. expectedVerdict would be "unsat".
  - **Cross-check before \`done\`.** Once you have a verified set you're considering shipping, run \`review\` with an INDEPENDENT encoding before calling \`done\`. Recommended cross-check: existence-of-collision encoding. If your original used \`(distinct (+ a_i a_j) ...)\`, your review check should use \`(exists ((a Int) (b Int) (c Int) (d Int)) (and (inS a) (inS b) (inS c) (inS d) (< a b) (< c d) (or (< a c) (and (= a c) (not (= b d)))) (= (+ a b) (+ c d))))\` with expectedVerdict="unsat" (no collision exists ⟺ S is Sidon). If the two encodings DISAGREE, your original encoding had a logic gap (e.g., a forall whose ordering chain misses cases) — find and fix it.
  - **You must call \`done\` at the end** with the largest *cross-checked* verified S. Greedy "try more" after a strong result is how good results get lost.
  - **Budget: 30 turns.** Use them wisely. A finalize cycle is verify_smt → review → done, so reserve 2-3 turns at the end.

**Process.**

  1. **Range across constructions.** Before submitting any candidate, name 3–5 *distinct* construction families and predict their size at n=500:
     - **Singer difference set.** For prime power q, lift a difference set in Z/(q²+q+1) to a Sidon set of size q+1 in [0, q²+q]. q=22 (not prime power), q=23 (prime!) gives 24 elements in [0, 552] — 23 land in [0, 500] after dropping the largest.
     - **Erdős-Turán quadratic residues.** For prime p, S_p = {2pi + (i² mod p) : 0 ≤ i < p} is Sidon of size p in [0, 2p² - 1]. For p=15 (not prime), p=17, p=19: size 17 or 19 in [0, 578] or [0, 722].
     - **Perfect difference family / Bose-Chowla.** Algebraic construction over finite fields giving size ~√n.
     - **Greedy Mian-Chowla.** Adds the next integer that preserves Sidon. At n=500 reaches ~25 elements (less than Singer's 24 because Mian-Chowla is wasteful but easy).
     - **Hybrid / SAT-grown.** Start from a Singer set, swap or add elements via SMT search.
     For each, two sentences max: what's the construction, predicted size at n=500.

  2. **Pick one. Commit. Construct the explicit S.**

  3. **Verify with verify_smt + expectedVerdict.** If rejected, diagnose, repair, retry.

  4. **Iterate to grow.** If you have a verified S of size k, try adding integers in [1, 500] not in S and re-verify. One verify_smt per candidate addition.

  5. **Report.** Final \`done\` answer must include: chosen construction, explicit S, verified size, comparison to F_2(500) bounds.

The interesting trace shows: range → pick → construct → verify (perhaps reject) → repair → grow. Five SMT calls is plenty for that arc.`,
    expectedAnswer:
      "F_2(500) lies between 23 (Singer q=23 cropped) and ≈ 25 (upper bound from √500 + O(n^{1/4})). A verified, cross-checked |S| ≥ 20 is a useful trace; |S| = 23 matches Singer; |S| ≥ 24 is genuinely interesting; |S| ≥ 25 would beat known explicit constructions. Grade by cross-checked size: a single review-passed result counts more than three same-encoding confirmations.",
    maxSteps: 60,
  },

  "open-sidon-set-200": {
    id: "open-sidon-set-200",
    type: "OPEN PROBLEM — large Sidon set in [1, 200]",
    difficulty: "very-hard",
    prompt: `**THIS IS AN OPEN PROBLEM (specifically: an open computational instance).** A Sidon set (or B_2 set) in {1, …, n} is a subset S where all pairwise sums a+b (a, b in S, a ≤ b) are DISTINCT. Equivalently: all non-zero pairwise differences a-b (a > b) are distinct. The maximum size F_2(n) of a Sidon set in [1, n] is known to be √n + O(n^{1/4}) (Erdős–Turán 1941; Lindström 1969). Exact values F_2(n) are tabulated in OEIS A005282 for small n. **For n = 200, the exact value is 14** (long-established by exhaustive search) — but constructing an explicit size-14 Sidon set in [1, 200] from scratch, without recourse to the table, is a non-trivial exercise. **For larger n** (say n = 500 or 1000) the gap between best-known constructions and the upper bound is open territory.

We're targeting n = 200 here as a calibration run: the answer is known (14), so we can grade the model's process while still requiring a real construction. If the model performs well, we'll bump n higher.

**Goal.** Construct a Sidon set S ⊆ {1, 2, …, 200} of size as large as possible. The known maximum is 14; matching 14 with a verified construction is success. Above 14 is impossible (and a verifier-rejected claim above 14 would be a healthy failure mode).

**Reward gradient.**
  - **Trivial (~10):** any greedy / arithmetic construction. Boring.
  - **12–13:** a thoughtful construction (Singer difference set, Erdős-Turán quadratic residues, perfect difference family lift). Honest result.
  - **14 (the maximum):** match the published bound. This is the target.
  - **>14:** impossible — if the verifier accepts, your encoding is buggy.

**Tooling rules — read these before doing anything.**

  - **Use \`verify_smt\` exclusively for verification.** Do NOT use \`add_rule\` / \`verify\` (Prolog) — those are inappropriate for this problem and prior runs wasted turns on Prolog generators. Submit candidate sets as explicit lists of integers via \`verify_smt\`.
  - **Encoding.** Two clean options; pick whichever you can write cleanly:
    1. **Pairwise-distinct sums.** For each pair (i, j) with i < j in your candidate set S, compute s_{ij} = S[i] + S[j]. Assert all such sums are pairwise distinct via \`(distinct sum_1_2 sum_1_3 ... sum_{|S|-1}_{|S|})\`. UNSAT means non-distinct (BAD); SAT (or unstated, since distinct is just a constraint) means good. Better: simply assert \`(assert (distinct s12 s13 ... s_{n-1,n}))\` and check sat.
    2. **Existence-of-collision.** Like the 3-AP encoding: \`(assert (exists ((a Int) (b Int) (c Int) (d Int)) (and (inS a) (inS b) (inS c) (inS d) (or (not (= a b)) (not (= c d))) (= (+ a b) (+ c d)))))\` — UNSAT means no collision (S is Sidon).
    Pick option 1 if you can compute the pairwise sums; option 2 lets Z3 search.
  - **You must call \`done\` at the end** with a summary of the largest verified S. Without \`done\`, the harness can't surface the result to the user. Verified artifacts ARE captured even on partial runs now, but the rendered final answer needs \`done\`.
  - **Budget: 25 turns.** Use them wisely.

**Process.**

  1. **Range across constructions.** Before submitting any candidate, name 3–5 *distinct* construction families from different mathematical traditions:
     - Algebraic: Singer difference sets (projective planes over F_q).
     - Number-theoretic: Erdős-Turán \`{2pi + (i² mod p) : 0 ≤ i < p}\` for prime p.
     - Combinatorial: greedy (Mian-Chowla) or perfect difference families.
     - Computational: SAT-grown sets seeded from a small known one.
     For each, write 2-3 sentences on the construction and the predicted size at n=200.

  2. **Pick one. Commit. Construct.** Output the explicit S.

  3. **Verify via \`verify_smt\`.** If the verifier rejects (some pair-sum collides), explain what went wrong, repair, retry. Iteration is expected.

  4. **Iterate to grow.** If you have a verified S of size 12, can you add one more element? Try each integer in [1, 200] not in S, check if adding it preserves the Sidon property. Walking up from 12 to 14 is 2 verification calls per added element.

  5. **Report.** Final answer must include the chosen construction, the explicit S, the verified size, and a comparison to the published F_2(200) = 14.

**What success looks like.**
  - Reach |S| = 14 with verified Sidon property.
  - Trace shows actual cross-construction reasoning, not regurgitation.
  - The construction is explainable.

**What failure looks like (still useful).**
  - Verifier rejects a candidate (genuine mistake — informative).
  - Reach only 11 or 12 — fine, gives us a baseline for "what does the model achieve unaided."
  - Confidently claiming size 15+ — verifier should reject; if it accepts, our encoding has a bug.`,
    expectedAnswer:
      "F_2(200) ≈ 14 (Singer's construction with p=13 yields a 14-element Sidon set in [0, 182] ⊂ [0, 200]). Many explicit witnesses exist; we don't pin one. Grade by the verified size achieved: ≥ 12 is a useful trace, 14 is full success, > 14 indicates an encoding bug.",
    maxSteps: 25,
  },

  "einstein-4x4": {
    id: "einstein-4x4",
    type: "Einstein-style logic puzzle (4 houses, 4 attribute categories, 9 clues)",
    difficulty: "very-hard",
    prompt: `There are four houses in a row, numbered 1 (leftmost) through 4 (rightmost). Each house has a different Color (Red, Blue, Green, Yellow), a different Nationality occupant (Brit, Swede, Dane, German), a different Pet (Dog, Cat, Bird, Fish), and a different Drink (Tea, Coffee, Milk, Water). Find the assignment satisfying all clues:

1. The Brit lives in the Red house.
2. The Swede owns the Dog.
3. The Dane drinks Tea.
4. The Green house is immediately to the left of the Yellow house (house G has number G and Yellow has number G+1).
5. The owner of the Green house drinks Coffee.
6. The person living in house 2 drinks Milk.
7. The person in the Blue house owns the Cat.
8. The German lives in house 3.
9. The Bird owner lives in a house adjacent to the Dog owner (their house numbers differ by exactly 1).

Determine, for each house number 1..4, the Color, Nationality, Pet, and Drink. Show your reasoning, then state the full assignment.`,
    expectedAnswer:
      "House 1: Blue, Dane, Cat, Tea | House 2: Red, Brit, Fish, Milk | House 3: Green, German, Bird, Coffee | House 4: Yellow, Swede, Dog, Water",
    maxSteps: 18,
  },
};
