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
  undoStep,
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

  it("undoStep rolls back tactics and restores the earlier goal state", async () => {
    const ps = await startSession({
      claim: "real ineq",
      theorem: "∀ x : ℝ, x ≤ x + 1",
    });
    const goalAfterStart = ps.goals;
    const r1 = await applyStep(ps, "intro x");
    expect(r1.status).toBe("open");
    const goalAfterIntro = ps.goals;
    expect(goalAfterIntro).not.toBe(goalAfterStart);

    // Undo by 1 → tactics empty, goal back to the post-start state.
    const u = await undoStep(ps, 1);
    expect(u.status).toBe("ok");
    expect(ps.tactics).toEqual([]);
    expect(ps.goals).toBe(goalAfterStart);
    expect(ps.status).toBe("open");

    // Re-apply intro then verify the proof can still close — the
    // proofState pointer must still be valid after undo.
    const r2 = await applyStep(ps, "intro x");
    expect(r2.status).toBe("open");
    const r3 = await applyStep(ps, "linarith");
    expect(r3.status).toBe("closed");
  }, 60_000);

  it("undoStep can roll back a closed proof (un-close)", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
    });
    const r1 = await applyStep(ps, "norm_num");
    expect(r1.status).toBe("closed");
    expect(ps.status).toBe("closed");
    const u = await undoStep(ps, 1);
    expect(u.status).toBe("ok");
    expect(ps.status).toBe("open");
    expect(ps.tactics).toEqual([]);
  }, 60_000);

  it("undoStep with steps > tactics-applied returns an error", async () => {
    const ps = await startSession({
      claim: "trivial",
      theorem: "1 + 1 = 2",
    });
    await applyStep(ps, "norm_num");
    const u = await undoStep(ps, 5); // only 1 tactic applied
    expect(u.status).toBe("error");
  }, 60_000);

  it("supports multiple sequential proof_start cycles without name collision", async () => {
    // Round 1 of Phase 3b review — without auto-uniquification, the
    // second proof_start would error because the REPL env still has
    // `_active_proof` defined from the first call.
    const ps1 = await startSession({ claim: "first", theorem: "1 + 1 = 2" });
    const r1 = await applyStep(ps1, "norm_num");
    expect(r1.status).toBe("closed");

    const ps2 = await startSession({ claim: "second", theorem: "2 + 2 = 4" });
    const r2 = await applyStep(ps2, "norm_num");
    expect(r2.status).toBe("closed");

    // Same again with the user supplying the *same* base name; the
    // harness should still uniquify under the hood.
    const ps3 = await startSession({
      claim: "third",
      theorem: "3 + 3 = 6",
      name: "shared_basename",
    });
    const r3 = await applyStep(ps3, "norm_num");
    expect(r3.status).toBe("closed");

    const ps4 = await startSession({
      claim: "fourth",
      theorem: "4 + 4 = 8",
      name: "shared_basename",
    });
    const r4 = await applyStep(ps4, "norm_num");
    expect(r4.status).toBe("closed");
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
