/**
 * Tests for the SWI-Prolog wrapper. We use SWI-Prolog 10.1.4 via
 * prolog-wasm-full so the canonical Prolog vocabulary (member/2,
 * append/3, length/2) and CLP(FD) (#=, ins, all_distinct, label) are
 * available out of the box.
 */

import { describe, it, expect } from "vitest";
import { runPrologSolver, createSession } from "../src/harness/prolog.js";

describe("prolog solver (SWI-WASM)", () => {
  it("solves knights-and-knaves: A says 'B is a knight'; B says 'A and C differ'; C says 'A is a knave'", async () => {
    const program = `
      solve(TA, TB, TC) :-
        member(TA, [knight, knave]),
        member(TB, [knight, knave]),
        member(TC, [knight, knave]),
        % A says "B is a knight"  →  A = knight ↔ B = knight
        (TA = knight -> TB = knight ; TB = knave),
        % B says "A and C are different"
        (TB = knight -> TA \\= TC ; TA = TC),
        % C says "A is a knave"
        (TC = knight -> TA = knave ; TA = knight).
    `;
    const result = await runPrologSolver({
      program,
      query: "solve(A, B, C).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers.length).toBe(1);
    const a = result.answers[0].bindings;
    expect(a.A).toBe("knight");
    expect(a.B).toBe("knight");
    expect(a.C).toBe("knave");
  });

  it("enumerates all answers up to the cap (using auto-loaded library(lists))", async () => {
    const result = await runPrologSolver({
      program: "",
      query: "member(X, [1, 2, 3, 4, 5]).",
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

  it("rejects a syntax-broken program at lint stage", async () => {
    // Lint now catches the missing `.` clause terminator before
    // SWI ever sees the input — better than the old behaviour of
    // silently returning 0 answers via SWI's recovery.
    const result = await runPrologSolver({
      program: "this is not prolog at all (((",
      query: "anything.",
    });
    expect(result.status).toBe("error");
    if (result.status !== "error") return;
    expect(result.error).toMatch(/lint|terminator/i);
  });

  it("CLP(FD): all_distinct + linear constraints (auto-loaded clpfd)", async () => {
    // Tiny puzzle: find {X, Y, Z} ⊂ {1..5} all distinct with X + Y + Z = 6.
    // Uniquely satisfied by {1, 2, 3}.
    const program = `
      tiny(X, Y, Z) :-
        [X, Y, Z] ins 1..5,
        all_distinct([X, Y, Z]),
        X + Y + Z #= 6,
        X #< Y, Y #< Z,
        label([X, Y, Z]).
    `;
    const result = await runPrologSolver({
      program,
      query: "tiny(X, Y, Z).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers).toHaveLength(1);
    expect(result.answers[0].bindings).toEqual({ X: "1", Y: "2", Z: "3" });
  });

  it("renders compound-term bindings in Prolog syntax", async () => {
    const result = await runPrologSolver({
      program: "",
      query: "X = foo(1, bar, [a, b]).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers[0].bindings.X).toBe("foo(1, bar, [a, b])");
  });

  it("renders pair (a-b) bindings in dash form", async () => {
    const result = await runPrologSolver({
      program: "",
      query: "X = key-value.",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers[0].bindings.X).toBe("key-value");
  });

  it("format/2,3 are available as built-ins (no library import needed)", async () => {
    // format(atom(Var), Fmt, Args) builds an atom from a template —
    // the prompt advertises this for constructing strings from
    // bindings. Verify it actually works without :- use_module.
    const result = await runPrologSolver({
      program: "",
      query: "format(atom(X), '~w-~d', [hello, 42]).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    expect(result.answers).toHaveLength(1);
    expect(result.answers[0].bindings.X).toBe("'hello-42'");
  });

  it("library(lists) and library(clpfd) are auto-loaded together", async () => {
    // The prompt says these two libraries are preloaded; verify both
    // operators/predicates are immediately usable in one program.
    const program = `
      pick(N, Out) :-
        length(Out, N),                 % library(lists)
        Out ins 1..9,                   % library(clpfd)
        all_distinct(Out),
        sum(Out, #=, 6),
        chain(Out, #<),                 % strict order — pin to one perm
        label(Out).
    `;
    const result = await runPrologSolver({
      program,
      query: "pick(3, Xs).",
    });
    expect(result.status).toBe("success");
    if (result.status !== "success") return;
    // 1+2+3 = 6 is the only 3-distinct ascending partition.
    expect(result.answers).toHaveLength(1);
    expect(result.answers[0].bindings.Xs).toBe("[1, 2, 3]");
  });

  it("a pre-aborted signal short-circuits the call", async () => {
    const ctrl = new AbortController();
    ctrl.abort();
    const result = await runPrologSolver({
      program: "noisy(1). noisy(2).",
      query: "noisy(X).",
      signal: ctrl.signal,
    });
    expect(result.status).toBe("error");
    if (result.status !== "error") return;
    expect(result.error).toBe("aborted");
  });

  it("isolates state between calls (consult unloaded after each)", async () => {
    const r1 = await runPrologSolver({
      program: "leaked(yes).",
      query: "leaked(X).",
    });
    expect(r1.status).toBe("success");
    if (r1.status !== "success") return;
    expect(r1.answers).toHaveLength(1);

    // Second call doesn't define leaked/1 — should be 0 answers, not
    // "yes" carried over.
    const r2 = await runPrologSolver({
      program: "",
      query: "leaked(X).",
    });
    expect(r2.status).toBe("success");
    if (r2.status !== "success") return;
    expect(r2.answers).toHaveLength(0);
  });

  it("strips trailing dot and ?- prefix from queries", async () => {
    const r1 = await runPrologSolver({ program: "", query: "X = 42." });
    const r2 = await runPrologSolver({ program: "", query: "?- X = 42." });
    const r3 = await runPrologSolver({ program: "", query: "X = 42" });
    for (const r of [r1, r2, r3]) {
      expect(r.status).toBe("success");
      if (r.status !== "success") continue;
      expect(r.answers[0].bindings.X).toBe("42");
    }
  });
});

describe("prolog session (persistent)", () => {
  it("assert + query: rules persist across calls within one session", async () => {
    const session = await createSession();
    try {
      let r = await session.assert("color(red). color(green). color(blue).");
      expect(r.status).toBe("ok");

      const q1 = await session.query("color(X).");
      expect(q1.status).toBe("success");
      if (q1.status === "success") {
        expect(q1.answers.map((a) => a.bindings.X)).toEqual([
          "red",
          "green",
          "blue",
        ]);
      }

      // Add more rules; previous ones must still be in scope.
      r = await session.assert("not_red(X) :- color(X), X \\= red.");
      expect(r.status).toBe("ok");

      const q2 = await session.query("not_red(X).");
      expect(q2.status).toBe("success");
      if (q2.status === "success") {
        expect(q2.answers.map((a) => a.bindings.X)).toEqual(["green", "blue"]);
      }
    } finally {
      await session.dispose();
    }
  });

  it("dispose: predicates from a disposed session don't leak to the next", async () => {
    const s1 = await createSession();
    await s1.assert("ephemeral(yes).");
    const q = await s1.query("ephemeral(X).");
    expect(q.status).toBe("success");
    if (q.status === "success") expect(q.answers).toHaveLength(1);
    await s1.dispose();

    // After dispose, a fresh session must NOT see ephemeral/1.
    const s2 = await createSession();
    try {
      const q2 = await s2.query("ephemeral(X).");
      expect(q2.status).toBe("success");
      if (q2.status === "success") expect(q2.answers).toHaveLength(0);
    } finally {
      await s2.dispose();
    }
  });

  it("incremental TDD: hypothesis-then-verify flow on knights & knaves", async () => {
    // Mirror the agent's intended workflow: assert clues bit by bit,
    // query for forced conclusions, verify each before continuing.
    const session = await createSession();
    try {
      // Step 1: assert the type domain + the three statements.
      let r = await session.assert(`
        person(a). person(b). person(c).
        type(knight). type(knave).
        assignment(A, B, C) :-
          member(A, [knight, knave]),
          member(B, [knight, knave]),
          member(C, [knight, knave]),
          % A says "B is a knight"
          (A = knight -> B = knight ; B = knave),
          % B says "A and C are different"
          (B = knight -> A \\= C ; A = C),
          % C says "A is a knave"
          (C = knight -> A = knave ; A = knight).
      `);
      expect(r.status).toBe("ok");

      // Step 2: hypothesize A = knight, verify it's the unique answer.
      const q1 = await session.query("assignment(A, B, C), A = knight.");
      expect(q1.status).toBe("success");
      if (q1.status === "success") expect(q1.answers).toHaveLength(1);

      // Step 3: counter-hypothesis — A = knave should yield 0 answers.
      const q2 = await session.query("assignment(A, B, C), A = knave.");
      expect(q2.status).toBe("success");
      if (q2.status === "success") expect(q2.answers).toHaveLength(0);
    } finally {
      await session.dispose();
    }
  });

  it("assert reports an error message instead of crashing the session", async () => {
    const session = await createSession();
    try {
      const r = await session.assert("");
      expect(r.status).toBe("error");
    } finally {
      await session.dispose();
    }
  });

  it("assume + discharge: scoped hypothetical reasoning", async () => {
    const session = await createSession();
    try {
      // Use a positive predicate (member/2 of an asserted list) instead
      // of \\+ on a dynamic-declared predicate — keeps the test self-
      // contained and resilient to global pl state from earlier tests.
      let r = await session.assert(`
        person(a). person(b).
        confirmed_knight(X) :- knight(X).
      `);
      expect(r.status).toBe("ok");

      // No knight/1 clauses defined yet → 0 confirmed knights.
      const q0 = await session.query("confirmed_knight(X).");
      expect(q0.status).toBe("success");
      if (q0.status === "success") expect(q0.answers).toHaveLength(0);

      // Open a hypothetical: assume a is a knight.
      r = await session.addNamed("h_knight_a", "knight(a).");
      expect(r.status).toBe("ok");

      // Under the assumption, a is now a confirmed knight.
      const qA = await session.query("confirmed_knight(X).");
      expect(qA.status).toBe("success");
      if (qA.status === "success") {
        expect(qA.answers).toHaveLength(1);
        expect(qA.answers[0].bindings.X).toBe("a");
      }

      // Discharge — assumption removed, knight(a) gone again.
      r = await session.retract("h_knight_a");
      expect(r.status).toBe("ok");

      const qAfter = await session.query("confirmed_knight(X).");
      expect(qAfter.status).toBe("success");
      if (qAfter.status === "success") expect(qAfter.answers).toHaveLength(0);
    } finally {
      await session.dispose();
    }
  });

  it("assume rejects duplicate names", async () => {
    const session = await createSession();
    try {
      const r1 = await session.addNamed("frame", "fact(1).");
      expect(r1.status).toBe("ok");
      const r2 = await session.addNamed("frame", "fact(2).");
      expect(r2.status).toBe("error");
    } finally {
      await session.dispose();
    }
  });

  it("discharge rejects unknown frame names", async () => {
    const session = await createSession();
    try {
      const r = await session.retract("never_assumed");
      expect(r.status).toBe("error");
    } finally {
      await session.dispose();
    }
  });

  it("commit makes a named rule no longer retractable", async () => {
    const session = await createSession();
    try {
      const a = await session.addNamed("foundation", "color(red).");
      expect(a.status).toBe("ok");

      const c = await session.commit("foundation");
      expect(c.status).toBe("ok");

      const r = await session.retract("foundation");
      expect(r.status).toBe("error");
      if (r.status === "error") expect(r.error).toMatch(/committed/);

      // The rule itself still works.
      const q = await session.query("color(X).");
      expect(q.status).toBe("success");
      if (q.status === "success") expect(q.answers).toHaveLength(1);
    } finally {
      await session.dispose();
    }
  });

  it("commit rejects unknown names and double-commits", async () => {
    const session = await createSession();
    try {
      const r1 = await session.commit("nope");
      expect(r1.status).toBe("error");

      await session.addNamed("twice", "fact(1).");
      const r2 = await session.commit("twice");
      expect(r2.status).toBe("ok");
      const r3 = await session.commit("twice");
      expect(r3.status).toBe("error");
    } finally {
      await session.dispose();
    }
  });

  it("a runaway query is stopped by the inference limit, not allowed to hang", async () => {
    // between/3 over 10^9 with an unsatisfiable secondary constraint
    // would otherwise grind for hours. With the harness wrapper in
    // place this should fail fast with an inference-limit error.
    // CI (Ubuntu x86_64) takes ~10-30s to hit 50M inferences; M-series
    // local is ~3-5s. The vitest timeout below has to cover the slow
    // path or this test pops on CI for the wrong reason.
    const session = await createSession();
    try {
      const t0 = Date.now();
      const r = await session.query(
        "between(1, 1000000000, X), X =:= -1",
      );
      const elapsed = Date.now() - t0;
      expect(r.status).toBe("error");
      if (r.status !== "error") return;
      expect(r.error).toMatch(/inference limit/);
      // Should bail well under 60s; verify the cap is doing its job.
      expect(elapsed).toBeLessThan(60_000);
    } finally {
      await session.dispose();
    }
  }, 60_000);
});
