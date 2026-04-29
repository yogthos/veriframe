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
