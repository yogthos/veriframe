/**
 * Tests for the Lean 4 verification backend. Requires:
 *   - elan / lean / lake on PATH (install via
 *     `curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh`)
 *   - Mathlib fetched into tools/lean-workspace (run `lake update` once)
 *
 * We don't have a way to skip these gracefully without breaking the
 * rest of the suite, so they will surface as failures if the
 * workspace isn't set up — that's the right behaviour: Phase-1
 * implementation needs Lean to be there.
 */

import { describe, it, expect } from "vitest";
import { runLean } from "../src/harness/lean.js";

describe("lean 4 backend", () => {
  it("verifies a trivial proof using a Mathlib tactic", () => {
    const r = runLean(`
      import Mathlib
      example : 2 + 2 = 4 := by norm_num
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.diagnostics.filter((d) => d.severity === "error")).toHaveLength(0);
  }, 120_000);

  it("rejects a wrong proof and surfaces the diagnostic", () => {
    const r = runLean(`
      import Mathlib
      example : 2 + 2 = 5 := by norm_num
    `);
    expect(r.status).toBe("error");
    if (r.status !== "error") return;
    const errs = r.diagnostics.filter((d) => d.severity === "error");
    expect(errs.length).toBeGreaterThan(0);
    // norm_num fails on a false equality, leaving "unsolved goals" or
    // "norm_num failed to close goal" — accept either wording.
    expect(errs[0].message.toLowerCase()).toMatch(/unsolved|failed/);
  }, 120_000);

  it("verifies a real-valued inequality via Mathlib", () => {
    // Genuine math, not just arithmetic identity.
    const r = runLean(`
      import Mathlib
      open Real

      example (x : ℝ) (h : 0 < x) : 0 < x + x := by linarith
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.diagnostics.filter((d) => d.severity === "error")).toHaveLength(0);
  }, 120_000);

  it("reports a syntax-error proof clearly", () => {
    const r = runLean(`
      import Mathlib
      this is not valid lean
    `);
    expect(r.status).toBe("error");
    if (r.status !== "error") return;
    expect(r.diagnostics.length).toBeGreaterThan(0);
  }, 120_000);
});
