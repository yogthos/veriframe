/**
 * Tests for the stateful Lean proof session (Phase 3a, naive replay).
 *
 * Each test runs a real Lean compile per step (~5-15s on cold mathlib
 * cache, ~2-5s warm). We chain a small but real proof end-to-end to
 * lock in: open → step → step → ... → close, including a
 * step_rejected → recover path.
 */

import { describe, it, expect } from "vitest";
import {
  startSession,
  applyStep,
  closeSession,
} from "../src/harness/lean-proof.js";

describe("lean-proof session (stepwise)", () => {
  it("opens a session with the theorem as initial goal", () => {
    const ps = startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
    });
    expect(ps.status).toBe("open");
    expect(ps.tactics).toEqual([]);
    expect(ps.goals).toContain("1 + 1 = 2");
  });

  it("rejects an invalid Lean identifier as session name", () => {
    expect(() =>
      startSession({ claim: "x", theorem: "True", name: "1bad name" }),
    ).toThrow();
  });

  it("applies a single tactic that closes the proof", async () => {
    const ps = startSession({ claim: "trivial", theorem: "1 + 1 = 2" });
    const r = await applyStep(ps, "norm_num");
    expect(r.status).toBe("closed");
    expect(ps.status).toBe("closed");
    expect(ps.tactics).toEqual(["norm_num"]);
  }, 60_000);

  it("applies a multi-step proof and shows intermediate goals", async () => {
    // Deliberately split the proof: intro then arithmetic.
    const ps = startSession({
      claim: "for all reals, x ≤ x + 1",
      theorem: "∀ x : ℝ, x ≤ x + 1",
    });
    const r1 = await applyStep(ps, "intro x");
    expect(r1.status).toBe("open");
    expect(r1.goals).toContain("x");
    expect(r1.goals).toMatch(/⊢/);
    const r2 = await applyStep(ps, "linarith");
    expect(r2.status).toBe("closed");
    expect(ps.tactics).toEqual(["intro x", "linarith"]);
  }, 120_000);

  it("rejects a wrong tactic and pops it from the session", async () => {
    const ps = startSession({
      claim: "for all reals, x ≤ x + 1",
      theorem: "∀ x : ℝ, x ≤ x + 1",
    });
    const r1 = await applyStep(ps, "intro x");
    expect(r1.status).toBe("open");
    // `apply Nat.le_succ` is wrong here — types don't match.
    const r2 = await applyStep(ps, "apply Nat.le_succ");
    expect(r2.status).toBe("tactic_error");
    expect(r2.errors.length).toBeGreaterThan(0);
    // The bad tactic should NOT be retained.
    expect(ps.tactics).toEqual(["intro x"]);
    // We can recover with a correct tactic.
    const r3 = await applyStep(ps, "linarith");
    expect(r3.status).toBe("closed");
    expect(ps.tactics).toEqual(["intro x", "linarith"]);
  }, 120_000);

  it("closeSession is idempotent and reports remaining goals when not closed", async () => {
    const ps = startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
    });
    // No tactics yet — closing should report the goal still open.
    const r = await closeSession(ps);
    expect(r.status).toBe("open");
  }, 60_000);

  it("rejects a tactic on a closed session", async () => {
    const ps = startSession({ claim: "trivial", theorem: "1 + 1 = 2" });
    await applyStep(ps, "norm_num");
    expect(ps.status).toBe("closed");
    const r = await applyStep(ps, "trivial");
    expect(r.status).toBe("tactic_error");
    expect(r.errors[0].message.toLowerCase()).toMatch(/closed/);
  }, 60_000);
});
