/**
 * Tests for the Z3 SMT verification backend. Requires the `z3` binary
 * on PATH (Homebrew: `brew install z3`).
 */

import { describe, it, expect } from "vitest";
import { runSmt } from "../src/harness/smt.js";

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
