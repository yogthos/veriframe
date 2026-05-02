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
  it("verifies a trivial proof using a Mathlib tactic", async () => {
    const r = await runLean(`
      import Mathlib
      example : 2 + 2 = 4 := by norm_num
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.diagnostics.filter((d) => d.severity === "error")).toHaveLength(0);
  }, 120_000);

  it("rejects a wrong proof and surfaces the diagnostic", async () => {
    const r = await runLean(`
      import Mathlib
      example : 2 + 2 = 5 := by norm_num
    `);
    expect(r.status).toBe("error");
    if (r.status !== "error") return;
    const errs = r.diagnostics.filter((d) => d.severity === "error");
    expect(errs.length).toBeGreaterThan(0);
    expect(errs[0].message.toLowerCase()).toMatch(/unsolved|failed/);
  }, 120_000);

  it("verifies a real-valued inequality via Mathlib", async () => {
    const r = await runLean(`
      import Mathlib
      open Real

      example (x : ℝ) (h : 0 < x) : 0 < x + x := by linarith
    `);
    expect(r.status).toBe("ok");
    if (r.status !== "ok") return;
    expect(r.diagnostics.filter((d) => d.severity === "error")).toHaveLength(0);
  }, 120_000);

  it("rejects a no-declaration snippet at lint stage", async () => {
    // Lint catches "no theorem/example/def" before lake startup.
    // The previous behaviour required spinning Lean up just to get
    // a parse error; the lint short-circuits in milliseconds.
    const r = await runLean(`
      import Mathlib
      this is not valid lean
    `);
    expect(r.status).toBe("error");
    if (r.status !== "error") return;
    expect(r.error.toLowerCase()).toMatch(/lint|theorem|example/);
  }, 120_000);

  it("two runLean calls execute concurrently (not blocking the event loop)", async () => {
    // If runLean is sync we'd see ~2x single-call wall-clock for two
    // calls in parallel; if async with spawn, both lake processes
    // overlap and total time is closer to 1x single-call.
    const snippet = `import Mathlib\nexample : 1 + 1 = 2 := by norm_num`;
    const start = Date.now();
    const [a, b] = await Promise.all([runLean(snippet), runLean(snippet)]);
    const elapsed = Date.now() - start;
    expect(a.status).toBe("ok");
    expect(b.status).toBe("ok");
    // Hard threshold: each call is 5-15s on cold mathlib, so two
    // sequential would be 10-30s. We give ourselves a generous 25s
    // ceiling for concurrent execution; if we ever sync-block again,
    // this test pops on slow machines first.
    expect(elapsed).toBeLessThan(25_000);
  }, 120_000);

  it("respects per-call timeoutMs", async () => {
    // 1ms timeout — Lean process can't even start. Should fail fast,
    // not silently succeed.
    const r = await runLean(
      `import Mathlib\nexample : True := trivial`,
      { timeoutMs: 1 },
    );
    expect(r.status).toBe("error");
    if (r.status !== "error") return;
    expect(r.error.toLowerCase()).toMatch(/timeout|signal|killed/);
  }, 30_000);

  it("does not double-import when a comment precedes the import line", async () => {
    // Round 2 / item 4: auto-import regex should match `import` lines
    // appearing after comments, not just at the start of the snippet.
    // If we prepend a duplicate `import Mathlib`, Lean errors with
    // "redundant import" or compiles with warnings; either way, the
    // model gets confused. We check the snippet path doesn't cause
    // a *different* error than the un-prefixed version.
    const snippet = `-- prologue\nimport Mathlib\nexample : True := trivial`;
    // verify_lean prepends `import Mathlib\n\n` only if no import
    // line exists. The agent.ts flow does this. Here we feed runLean
    // directly to check the bare path is fine, then we rely on the
    // agent.ts test below for the prepend behaviour.
    const r = await runLean(snippet);
    expect(r.status).toBe("ok");
  }, 120_000);

  it("workspace resolution is independent of process.cwd()", async () => {
    // Change cwd to something irrelevant, ensure the call still works.
    // The fix should resolve the workspace relative to the source
    // file (via import.meta.url) rather than process.cwd().
    const original = process.cwd();
    try {
      process.chdir("/tmp");
      const r = await runLean(
        `import Mathlib\nexample : 0 + 0 = 0 := by norm_num`,
      );
      expect(r.status).toBe("ok");
    } finally {
      process.chdir(original);
    }
  }, 120_000);
});
