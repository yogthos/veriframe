/**
 * Tests for the Tau Prolog wrapper used as a relational reasoning
 * layer alongside Z3 in the agent. Smoke-tests use knights-and-knaves
 * style problems since they're Prolog's natural domain.
 */

import { describe, it, expect } from "vitest";
import { runPrologSolver } from "../src/harness/prolog.js";

describe("prolog solver", () => {
  it("solves knights-and-knaves: A says 'B is a knight'; B says 'A and C differ'; C says 'A is a knave'", async () => {
    // True ↔ knight, False ↔ knave. Each says clause asserts the
    // speaker's type ↔ truth of their statement.
    const program = `
      person(a). person(b). person(c).
      type(knight). type(knave).
      types_match(knight, true).
      types_match(knave, false).

      different(X, X) :- !, fail.
      different(_, _).

      solve(TA, TB, TC) :-
        type(TA), type(TB), type(TC),
        % A says "B is a knight"  →  A = knight ↔ B = knight
        (TA = knight, TB = knight ; TA = knave, TB = knave),
        % B says "A and C are different" →  B = knight ↔ A ≠ C
        (TB = knight, different(TA, TC) ; TB = knave, TA = TC),
        % C says "A is a knave" →  C = knight ↔ A = knave
        (TC = knight, TA = knave ; TC = knave, TA = knight).
    `;
    const result = await runPrologSolver({
      program,
      query: "solve(A, B, C).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    // Should be exactly one assignment
    expect(result.answers.length).toBe(1);
    const a = result.answers[0].bindings;
    expect(a.A).toBe("knight");
    expect(a.B).toBe("knight");
    expect(a.C).toBe("knave");
  });

  it("enumerates all answers up to the cap", async () => {
    const program = `
      member(X, [X|_]).
      member(X, [_|T]) :- member(X, T).
    `;
    const result = await runPrologSolver({
      program,
      query: "member(X, [1,2,3,4,5]).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers).toHaveLength(5);
    expect(result.answers.map((a) => a.bindings.X)).toEqual([
      "1",
      "2",
      "3",
      "4",
      "5",
    ]);
  });

  it("returns no answers when the goal is unsatisfiable", async () => {
    const program = `
      colour(red). colour(green). colour(blue).
      not_red(X) :- colour(X), X \\= red.
    `;
    const result = await runPrologSolver({
      program,
      query: "not_red(red).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers).toHaveLength(0);
  });

  it("reports a syntax error in the program", async () => {
    const result = await runPrologSolver({
      program: "this is not prolog at all (((",
      query: "anything.",
    });
    expect(result.status).toBe("error");
  });
});
