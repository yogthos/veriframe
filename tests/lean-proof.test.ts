/**
 * Tests for the stateful Lean proof session (Phase 3b, REPL backend).
 *
 * The backend is a long-lived `leanprover-community/repl` subprocess
 * that loads Mathlib once. First test in a run pays the import cost
 * (~10-30s); subsequent tests reuse the warm REPL and run sub-second.
 */

import { describe, it, expect, afterAll } from "vitest";
import {
  startSession,
  applyStep,
  closeSession,
} from "../src/harness/lean-proof.js";
import { stopRepl } from "../src/harness/lean-repl.js";

describe("lean-proof session (stepwise, REPL-backed)", () => {
  afterAll(() => {
    stopRepl();
  });

  it("opens a session with the theorem statement as initial goal", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
    });
    expect(ps.status).toBe("open");
    expect(ps.tactics).toEqual([]);
    expect(ps.goals).toMatch(/⊢/);
  }, 120_000);

  it("rejects an invalid Lean identifier as session name", async () => {
    await expect(
      startSession({ claim: "x", theorem: "True", name: "1bad name" }),
    ).rejects.toThrow();
  });

  it("rejects a malformed theorem statement", async () => {
    // Blatantly bad type — REPL should report an error from openProof.
    await expect(
      startSession({ claim: "junk", theorem: "this is not a type at all" }),
    ).rejects.toThrow();
  }, 60_000);

  it("applies a single tactic that closes the proof", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
      name: "_t_close_one",
    });
    const r = await applyStep(ps, "norm_num");
    expect(r.status).toBe("closed");
    expect(ps.status).toBe("closed");
    expect(ps.tactics).toEqual(["norm_num"]);
  }, 60_000);

  it("applies a multi-step proof and surfaces intermediate goals", async () => {
    const ps = await startSession({
      claim: "for all reals, x ≤ x + 1",
      theorem: "∀ x : ℝ, x ≤ x + 1",
      name: "_t_multi",
    });
    const r1 = await applyStep(ps, "intro x");
    expect(r1.status).toBe("open");
    // The intermediate state should mention `x` and a goal.
    expect(r1.goals).toContain("x");
    expect(r1.goals).toMatch(/⊢/);
    const r2 = await applyStep(ps, "linarith");
    expect(r2.status).toBe("closed");
    expect(ps.tactics).toEqual(["intro x", "linarith"]);
  }, 60_000);

  it("rejects a wrong tactic and keeps the previous coherent state", async () => {
    const ps = await startSession({
      claim: "for all reals, x ≤ x + 1",
      theorem: "∀ x : ℝ, x ≤ x + 1",
      name: "_t_recover",
    });
    const r1 = await applyStep(ps, "intro x");
    expect(r1.status).toBe("open");
    // Reference an identifier that doesn't exist anywhere — guaranteed
    // hard error from Lean (`apply` to a wrong-type lemma sometimes
    // succeeds with unification metavariables, so we use a clearly
    // undefined name instead).
    const r2 = await applyStep(ps, "exact this_lemma_definitely_does_not_exist");
    expect(r2.status).toBe("tactic_error");
    expect(r2.errors.length).toBeGreaterThan(0);
    // Bad tactic should not be retained.
    expect(ps.tactics).toEqual(["intro x"]);
    // We can recover with a correct tactic.
    const r3 = await applyStep(ps, "linarith");
    expect(r3.status).toBe("closed");
    expect(ps.tactics).toEqual(["intro x", "linarith"]);
  }, 60_000);

  it("closeSession reports open when no tactics applied yet", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
      name: "_t_close_empty",
    });
    const r = await closeSession(ps);
    expect(r.status).toBe("open");
  }, 60_000);

  it("rejects a tactic applied to a closed session", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
      name: "_t_already_closed",
    });
    await applyStep(ps, "norm_num");
    expect(ps.status).toBe("closed");
    const r = await applyStep(ps, "trivial");
    expect(r.status).toBe("tactic_error");
    expect(r.errors[0].data?.toLowerCase()).toMatch(/closed/);
  }, 60_000);
});
