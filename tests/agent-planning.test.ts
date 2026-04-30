/**
 * Smoke tests for the planning skeleton generator. Z3 is loaded in
 * the harness anyway, so we use it directly to validate that the
 * generated SMT-LIB is well-formed and produces the expected SAT/UNSAT
 * verdicts on tiny test problems.
 */

import { describe, it, expect } from "vitest";
import { createIncrementalSolver } from "../src/harness/solver.js";

// We reach into the agent module to test the generator. It's not in
// the public surface but the test verifies the pure function in
// isolation, which is useful regardless.
async function generateAndCheck(spec: unknown): Promise<{
  status: "sat" | "unsat" | "unknown";
  smt: string;
  model?: Record<string, string>;
}> {
  const { validatePlanningSpec, generatePlanningSmt } = await import(
    "../src/harness/agent-planning.js"
  );
  const validated = validatePlanningSpec(spec);
  const smt = generatePlanningSmt(validated);
  const solver = await createIncrementalSolver();
  try {
    solver.assert(smt);
    const status = await solver.check();
    if (status === "sat") {
      const model = solver.getModel();
      return { status, smt, model };
    }
    return { status, smt };
  } finally {
    solver.dispose();
  }
}

describe("planning skeleton generator", () => {
  it("solves a trivial 1-step toggle problem", async () => {
    const spec = {
      horizon: 1,
      state_vars: [{ name: "x", sort: "Bool" }],
      initial: { x: false },
      goal: { x: true },
      actions: [
        {
          name: "toggle_x",
          changes: ["x"],
          predicate: "(= x_tp1 (not x_t))",
        },
      ],
    };
    const result = await generateAndCheck(spec);
    expect(result.status).toBe("sat");
    expect(result.model?.x_0).toBe("false");
    expect(result.model?.x_1).toBe("true");
  });

  it("returns UNSAT when horizon is too small", async () => {
    // Need 2 transitions to flip from false → true → false → true,
    // so K=1 is not enough.
    const spec = {
      horizon: 1,
      state_vars: [{ name: "x", sort: "Bool" }],
      initial: { x: false },
      goal: { x: false },     // start false, end false; 1 transition
      // Transition mandatory: each step must toggle (no "noop" action).
      actions: [
        {
          name: "toggle_x",
          changes: ["x"],
          predicate: "(= x_tp1 (not x_t))",
        },
      ],
    };
    const result = await generateAndCheck(spec);
    // K=1 means one mandatory toggle from false. Goal=false. Should be unsat.
    expect(result.status).toBe("unsat");
  });

  it("emits frame axioms for non-changed vars", async () => {
    // Two vars, one action that only changes x. y must stay equal at
    // t=0 and t=1.
    const spec = {
      horizon: 1,
      state_vars: [
        { name: "x", sort: "Bool" },
        { name: "y", sort: "Bool" },
      ],
      initial: { x: false, y: true },
      goal: { x: true, y: true },
      actions: [
        {
          name: "set_x",
          changes: ["x"],
          predicate: "x_tp1",   // x becomes true
        },
      ],
    };
    const result = await generateAndCheck(spec);
    expect(result.status).toBe("sat");
    // Frame axiom must hold: y_0 == y_1 == true
    expect(result.model?.y_0).toBe("true");
    expect(result.model?.y_1).toBe("true");
    expect(result.smt).toMatch(/\(= y_0 y_1\)/);
  });

  it("expands invariants over every timestep", async () => {
    const spec = {
      horizon: 2,
      state_vars: [{ name: "x", sort: "Int", domain: [0, 5] }],
      initial: { x: 0 },
      goal: { x: 2 },
      invariants: ["(<= x_t 3)"],
      actions: [
        {
          name: "inc",
          changes: ["x"],
          predicate: "(= x_tp1 (+ x_t 1))",
        },
      ],
    };
    const result = await generateAndCheck(spec);
    expect(result.status).toBe("sat");
    expect(result.smt).toMatch(/\(<= x_0 3\)/);
    expect(result.smt).toMatch(/\(<= x_1 3\)/);
    expect(result.smt).toMatch(/\(<= x_2 3\)/);
  });

  it("rejects malformed specs", async () => {
    const { validatePlanningSpec } = await import(
      "../src/harness/agent-planning.js"
    );
    expect(() => validatePlanningSpec({ horizon: -1 })).toThrow();
    expect(() => validatePlanningSpec({ horizon: 1 })).toThrow();
    expect(() => validatePlanningSpec({
      horizon: 1,
      state_vars: [{ name: "x", sort: "Bool" }],
      initial: { y: false },           // y not declared
      goal: { x: true },
      actions: [{ name: "a", changes: ["x"], predicate: "true" }],
    })).toThrow(/initial.*y/);
  });
});
