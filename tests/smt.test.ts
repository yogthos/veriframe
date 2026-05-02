/**
 * Tests for the Z3 SMT verification backend. Requires the `z3` binary
 * on PATH (Homebrew: `brew install z3`).
 */

import { describe, it, expect } from "vitest";
import { runSmt, parseModel, checkWitnessAgainstFormula } from "../src/harness/smt.js";

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

  it("rejects input that produced Z3 parse errors (catches the n=500 ellipsis false positive)", () => {
    // The model wrote ellipsis-shorthand SMT-LIB. Z3 parsed only the
    // declarations before the `...`, errored on the rest, and then
    // emitted `sat` for the empty constraint set that survived. The
    // harness used to record this as a confirmed verdict; now it
    // refuses to interpret the verdict whenever Z3 emitted errors.
    const r = runSmt(`
      (declare-const a1 Int) (declare-const a2 Int) ... (declare-const a23 Int)
      (assert (and (>= a1 1) (<= a1 500) ... (>= a23 1) (<= a23 500)))
      (assert (distinct a1 a2 ... a23))
      (check-sat)
    `);
    expect(r.status).toBe("error");
    if (r.status === "error") {
      expect(r.error).toMatch(/parse|type|error/i);
    }
  });

  it("treats any (error ...) line in stdout as a hard error", () => {
    // Build a snippet that references an undeclared symbol — Z3 will
    // emit `(error "...")` and then SAT the rest. We must surface the
    // error, not the misleading SAT.
    const r = runSmt(`
      (assert (> z 0))
      (declare-const x Int)
      (assert (= x 1))
      (check-sat)
    `);
    expect(r.status).toBe("error");
  });
});

describe("checkWitnessAgainstFormula (Layer 2 witness sanity)", () => {
  it("flags a (distinct ...) violated by a duplicate witness value", () => {
    const smt = `(declare-const a1 Int) (declare-const a2 Int) (declare-const a3 Int)
      (assert (distinct a1 a2 a3)) (check-sat)`;
    const witness = { a1: "0", a2: "0", a3: "5" };
    const issues = checkWitnessAgainstFormula(smt, witness);
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.join(" ")).toMatch(/distinct.*not distinct/i);
  });

  it("accepts a (distinct ...) when the witness values truly differ", () => {
    const smt = `(declare-const a Int) (declare-const b Int) (assert (distinct a b))`;
    const witness = { a: "1", b: "2" };
    expect(checkWitnessAgainstFormula(smt, witness)).toEqual([]);
  });

  it("flags a witness value below an asserted lower bound", () => {
    const smt = `(declare-const x Int) (assert (>= x 1)) (check-sat)`;
    const witness = { x: "0" };
    const issues = checkWitnessAgainstFormula(smt, witness);
    expect(issues.join(" ")).toMatch(/below the bound/);
  });

  it("flags a witness value above an asserted upper bound", () => {
    const smt = `(declare-const x Int) (assert (<= x 500)) (check-sat)`;
    const witness = { x: "600" };
    const issues = checkWitnessAgainstFormula(smt, witness);
    expect(issues.join(" ")).toMatch(/above the bound/);
  });

  it("flags a witness violating a direct equality", () => {
    const smt = `(declare-const a Int) (assert (= a 7))`;
    const witness = { a: "8" };
    const issues = checkWitnessAgainstFormula(smt, witness);
    expect(issues.length).toBeGreaterThan(0);
  });

  it("ignores non-bare-identifier args inside (distinct ...)", () => {
    // (distinct (+ a b) (+ c d)) — the distinct args are expressions,
    // not bare names. We can't validate this from the witness alone,
    // so we don't try.
    const smt = `(assert (distinct (+ a b) (+ c d)))`;
    const witness = { a: "0", b: "0", c: "0", d: "0" };
    expect(checkWitnessAgainstFormula(smt, witness)).toEqual([]);
  });

  it("handles negative integers in the witness via the (- N) syntax", () => {
    const smt = `(declare-const x Int) (assert (>= x 1))`;
    const witness = { x: "(- 3)" };
    const issues = checkWitnessAgainstFormula(smt, witness);
    expect(issues.join(" ")).toMatch(/below the bound/);
  });
});
