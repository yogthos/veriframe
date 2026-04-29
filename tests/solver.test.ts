import { describe, it, expect, afterEach } from "vitest";
import { createIncrementalSolver } from "../src/harness/solver.js";
import type { IncrementalSolver } from "../src/types.js";

describe("IncrementalSolver", () => {
  let solver: IncrementalSolver;

  afterEach(() => {
    solver?.dispose();
  });

  it("asserts a simple constraint and returns SAT", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (> x 0))");
    const status = await solver.check();
    expect(status).toBe("sat");
  });

  it("detects contradiction and returns UNSAT", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (> x 10))");
    solver.assert("(assert (< x 5))");
    const status = await solver.check();
    expect(status).toBe("unsat");
  });

  it("extracts unsat core from contradictory constraints", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (! (> x 10) :named gt10))");
    solver.assert("(assert (! (< x 5) :named lt5))");
    const status = await solver.check();
    expect(status).toBe("unsat");
    const core = solver.unsatCore();
    expect(core.length).toBeGreaterThan(0);
    const coreStr = core.join(" ");
    expect(coreStr).toMatch(/gt10|lt5/);
  });

  it("push/pop: saves and restores solver state", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (> x 0))");

    // Verify base state is SAT
    expect(await solver.check()).toBe("sat");

    // Push: save current state, then add a contradiction
    solver.push();
    solver.assert("(assert (< x 0))");
    expect(await solver.check()).toBe("unsat");

    // Pop: revert to before the contradiction
    solver.pop();
    expect(await solver.check()).toBe("sat");
  });

  it("multiple push levels with incremental assertions", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (> x 0))");
    expect(await solver.check()).toBe("sat");

    // Level 1: add constraint
    solver.push();
    solver.assert("(assert (< x 10))");
    expect(await solver.check()).toBe("sat");

    // Level 2: add contradiction
    solver.push();
    solver.assert("(assert (> x 20))");
    expect(await solver.check()).toBe("unsat");

    // Pop level 2: back to level 1 (still SAT)
    solver.pop();
    expect(await solver.check()).toBe("sat");

    // Pop level 1: back to base (still SAT)
    solver.pop();
    expect(await solver.check()).toBe("sat");
  });

  it("gets model for SAT result", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (= x 42))");
    const status = await solver.check();
    expect(status).toBe("sat");
    const model = solver.getModel();
    expect(model.x).toBe("42");
  });

  it("throws on unsatCore when last check was SAT", async () => {
    solver = await createIncrementalSolver();
    solver.assert("(declare-const x Int)");
    solver.assert("(assert (= x 1))");
    await solver.check(); // SAT
    expect(() => solver.unsatCore()).toThrow();
  });

  it("dispose prevents further operations", async () => {
    solver = await createIncrementalSolver();
    solver.dispose();
    await expect(solver.check()).rejects.toThrow();
  });
});
