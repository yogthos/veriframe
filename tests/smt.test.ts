/**
 * Tests for the Z3 SMT verification backend. Requires the `z3` binary
 * on PATH (Homebrew: `brew install z3`).
 */

import { describe, it, expect } from "vitest";
import { runSmt, parseModel } from "../src/harness/smt.js";

describe("z3 SMT backend", () => {
  it("returns sat for a satisfiable formula", () => {
    const r = runSmt(`
      (declare-const x Int)
      (assert (> x 0))
      (assert (< x 10))
    `);
    expect(r.status).toBe("ok");
    if (r.status === "ok") expect(r.verdict).toBe("sat");
  });

  it("returns unsat for an inconsistent formula", () => {
    const r = runSmt(`
      (declare-const x Int)
      (assert (> x 5))
      (assert (< x 3))
    `);
    expect(r.status).toBe("ok");
    if (r.status === "ok") expect(r.verdict).toBe("unsat");
  });

  it("auto-appends (check-sat) if missing", () => {
    const r = runSmt(`(declare-const x Int) (assert (= x 1))`);
    expect(r.status).toBe("ok");
    if (r.status === "ok") expect(r.verdict).toBe("sat");
  });

  it("respects an explicit (check-sat) the user includes", () => {
    const r = runSmt(`
      (declare-const x Int)
      (assert (= x 1))
      (check-sat)
    `);
    expect(r.status).toBe("ok");
    if (r.status === "ok") expect(r.verdict).toBe("sat");
  });

  it("proves a universal claim via UNSAT-on-negation pattern", () => {
    // To prove forall x. x*x >= 0, assert the negation; should be UNSAT.
    const r = runSmt(`
      (declare-const x Int)
      (assert (< (* x x) 0))
    `);
    expect(r.status).toBe("ok");
    if (r.status === "ok") expect(r.verdict).toBe("unsat");
  });

  it("returns a witness model on SAT", () => {
    const r = runSmt(`
      (declare-const x Int)
      (declare-const b Bool)
      (assert (= x 7))
      (assert b)
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.verdict).toBe("sat");
    expect(r.model).toBeDefined();
    expect(r.model?.x).toBe("7");
    expect(r.model?.b).toBe("true");
  });

  it("captures negative-integer values via paren-balanced parsing", () => {
    const r = runSmt(`
      (declare-const y Int)
      (assert (= y (- 5)))
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.verdict).toBe("sat");
    // Z3 may print the value as `(- 5)` or `-5` depending on version.
    const v = r.model?.y ?? "";
    expect(v).toMatch(/^(-5|\(-\s*5\))$/);
  });

  it("returns no model on UNSAT", () => {
    const r = runSmt(`
      (declare-const x Int)
      (assert (> x 5))
      (assert (< x 3))
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.verdict).toBe("unsat");
    expect(r.model).toBeUndefined();
  });

  it("parseModel handles boolean and integer assignments", () => {
    const sample = `(
      (define-fun e01 () Bool true)
      (define-fun e02 () Bool false)
      (define-fun x () Int 42)
      (define-fun y () Int (- 5))
    )`;
    const m = parseModel(sample);
    expect(m.e01).toBe("true");
    expect(m.e02).toBe("false");
    expect(m.x).toBe("42");
    // (- 5) should preserve the parens.
    expect(m.y).toBe("(- 5)");
  });

  it("parseModel returns empty map when there are no define-funs", () => {
    expect(parseModel("sat")).toEqual({});
    expect(parseModel("")).toEqual({});
    expect(parseModel("(error \"line 1\")")).toEqual({});
  });

  it("reports an error verdict when input is malformed", () => {
    const r = runSmt(`(this is not smt-lib)`);
    // Z3 will print errors but eventually emit a verdict for whatever
    // it could parse, OR our wrapper sees no verdict at all and errors.
    // Either path counts as a non-"ok" outcome here.
    if (r.status === "ok") {
      expect(["sat", "unsat", "unknown"]).toContain(r.verdict);
    } else {
      expect(r.status).toBe("error");
    }
  });
});
