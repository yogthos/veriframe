/**
 * Unit tests for agent.ts helpers we want to lock in.
 * Avoids spinning up the full agent run.
 */

import { describe, it, expect } from "vitest";
import {
  leanSnippetHasImport,
  renderBranchHistory,
  repairControlCharsInJsonStrings,
  checkAnswerCoversArtifacts,
  type BranchState,
  type GlobalRunState,
} from "../src/harness/agent.js";

function fakeBranch(over: Partial<BranchState> & { id: string }): BranchState {
  return {
    id: over.id,
    status: over.status ?? "active",
    inactiveReason: over.inactiveReason,
    problem: over.problem ?? "",
    finalAnswer: over.finalAnswer ?? null,
    turns: over.turns ?? [],
    // prolog/leanProof are non-trivial to construct; the helpers we
    // test here don't touch them, so cast through unknown.
    prolog: (over.prolog ?? {}) as BranchState["prolog"],
    assertedBytes: over.assertedBytes ?? 0,
    verifyHistory: over.verifyHistory ?? [],
    hintCooldownTurns: over.hintCooldownTurns ?? 0,
    leanProof: over.leanProof ?? null,
    verifiedArtifacts: over.verifiedArtifacts ?? [],
    consecutiveFailures: over.consecutiveFailures ?? 0,
    leanEnv: over.leanEnv ?? null,
    lastReview: over.lastReview ?? null,
    milestonePromptInjected: over.milestonePromptInjected ?? false,
    messages: over.messages ?? [],
  };
}

function fakeState(branches: BranchState[]): GlobalRunState {
  return {
    problem: "",
    branches,
    globalFailureLog: [],
    doneBranchId: null,
    finalAnswer: null,
  };
}

describe("leanSnippetHasImport", () => {
  it("detects a leading import", () => {
    expect(leanSnippetHasImport("import Mathlib\nexample : True := trivial"))
      .toBe(true);
  });

  it("detects an import after a line comment (Round 2 fix)", () => {
    expect(
      leanSnippetHasImport("-- prologue\nimport Mathlib\nexample : True := trivial"),
    ).toBe(true);
  });

  it("detects an import after a blank line / whitespace", () => {
    expect(leanSnippetHasImport("\n\n  import Mathlib\nexample : True := trivial"))
      .toBe(true);
  });

  it("returns false when no import is present", () => {
    expect(leanSnippetHasImport("example : True := trivial")).toBe(false);
  });

  it("returns false for empty input", () => {
    expect(leanSnippetHasImport("")).toBe(false);
  });

  it("matches a specific Mathlib submodule import", () => {
    expect(leanSnippetHasImport("import Mathlib.Topology.Basic\nexample : True := trivial"))
      .toBe(true);
  });
});

describe("repairControlCharsInJsonStrings", () => {
  it("leaves well-formed JSON untouched", () => {
    const input = '{"name": "verify_smt", "args": {"claim": "x = 1", "smtlib": "(assert true)"}}';
    expect(repairControlCharsInJsonStrings(input)).toBe(input);
  });

  it("escapes a raw newline inside a string value (the n=500 Sidon failure mode)", () => {
    const input = `{"name": "verify_smt", "args": {"smtlib": "(declare-const x Int)\n(assert (= x 1))"}}`;
    const out = repairControlCharsInJsonStrings(input);
    expect(out).toContain("\\n");
    // The repaired version should now parse.
    expect(() => JSON.parse(out)).not.toThrow();
  });

  it("escapes carriage returns and tabs too", () => {
    const input = `{"k": "a\rb\tc"}`;
    const out = repairControlCharsInJsonStrings(input);
    expect(out).toBe('{"k": "a\\rb\\tc"}');
    expect(() => JSON.parse(out)).not.toThrow();
  });

  it("does not double-escape already-escaped sequences", () => {
    // The model wrote \n correctly; we shouldn't turn it into \\n.
    const input = '{"k": "a\\nb"}';
    const out = repairControlCharsInJsonStrings(input);
    expect(out).toBe(input);
  });

  it("does not touch newlines OUTSIDE strings (formatting whitespace)", () => {
    const input = '{\n  "k": "v"\n}';
    const out = repairControlCharsInJsonStrings(input);
    // Newlines outside the string are preserved verbatim — the JSON
    // parser handles those fine.
    expect(out).toBe(input);
  });

  it("handles escaped quotes inside strings without breaking state", () => {
    const input = '{"k": "say \\"hi\\" then\nbye"}';
    const out = repairControlCharsInJsonStrings(input);
    // The escaped quotes should not flip the in-string flag; the
    // raw newline mid-string should still get repaired.
    expect(out).toBe('{"k": "say \\"hi\\" then\\nbye"}');
    expect(() => JSON.parse(out)).not.toThrow();
  });
});

describe("checkAnswerCoversArtifacts (done-gate substantiation)", () => {
  const mkArtifact = (claim: string) => ({
    kind: "smt" as const,
    claim,
    code: "",
    claimStatus: "confirmed" as const,
  });

  it("flags the Schur cheat: verified [1,13] 3-coloring, shipped [1,44] 4-coloring", () => {
    const artifact = mkArtifact(
      "3-coloring of [1,13] via recursive construction has no monochromatic Schur triple",
    );
    const cheatAnswer = "Goal achieved: a 4-coloring of [1,44] periodic pattern with no monochromatic Schur triple, verified via verify_template.";
    const mismatches = checkAnswerCoversArtifacts(cheatAnswer, [artifact]);
    expect(mismatches.length).toBeGreaterThan(0);
    expect(mismatches[0].missing.join(",")).toMatch(/13|recursive/);
  });

  it("accepts an honest answer that mentions the verified claim", () => {
    const artifact = mkArtifact(
      "3-coloring of [1,13] via recursive construction has no monochromatic Schur triple",
    );
    const honestAnswer = "Goal: 3-coloring of [1,13] verified, no monochromatic Schur triple. Used the recursive Schur construction. Settles S(3) ≥ 13.";
    const mismatches = checkAnswerCoversArtifacts(honestAnswer, [artifact]);
    expect(mismatches).toEqual([]);
  });

  it("accepts a Sidon Mian-Chowla 20 answer", () => {
    const artifact = mkArtifact(
      "Mian-Chowla 20 is a Sidon set in [1, 500]",
    );
    const answer = "Verified Sidon set of size 20 in [1, 500] using the Mian-Chowla truncated sequence: {1, 2, 4, 8, 13, 21, ...}.";
    const mismatches = checkAnswerCoversArtifacts(answer, [artifact]);
    expect(mismatches).toEqual([]);
  });

  it("accepts a thorough multi-artifact summary that names each verified result", () => {
    const a1 = mkArtifact("Definitions of IsUnionClosed and FranklConjecture compile against Mathlib");
    const a2 = mkArtifact("Level 2c: For union-closed F with |F| = 2, Frankl's conjecture holds");
    const answer = "Verified: Definitions of IsUnionClosed and FranklConjecture compile against Mathlib (Level 1 baseline). Level 2c: For union-closed F with |F| = 2, the Frankl conjecture holds (Lean proof closes via omega).";
    const mismatches = checkAnswerCoversArtifacts(answer, [a1, a2]);
    expect(mismatches).toEqual([]);
  });

  it("flags a partial summary that drops one of the verified artifacts", () => {
    // The model verified two things and shipped only the second.
    // The L1 artifact's distinctive identifiers (IsUnionClosed,
    // FranklConjecture) don't appear in the answer; the gate should
    // catch that the answer is under-counting.
    const a1 = mkArtifact("Definitions of IsUnionClosed and FranklConjecture compile against Mathlib");
    const a2 = mkArtifact("Level 2c: For union-closed F with |F| = 2, Frankl's conjecture holds");
    const answer = "Verified: Level 2c — for union-closed F with |F| = 2, the Frankl conjecture holds.";
    const mismatches = checkAnswerCoversArtifacts(answer, [a1, a2]);
    // a1 should be flagged (its identifiers don't appear)
    expect(mismatches.length).toBe(1);
    expect(mismatches[0].claim).toContain("IsUnionClosed");
  });

  it("flags when the answer omits the verified problem's specific numbers", () => {
    const artifact = mkArtifact(
      "Verified Mian-Chowla Sidon set of size 20 in [1, 500]",
    );
    // Answer claims a totally different size and range
    const wrong = "Sidon set of size 35 in [1, 1000] using Singer's construction";
    const mismatches = checkAnswerCoversArtifacts(wrong, [artifact]);
    expect(mismatches.length).toBeGreaterThan(0);
  });

  it("returns no mismatches when there are no recent artifacts", () => {
    expect(checkAnswerCoversArtifacts("anything", [])).toEqual([]);
  });
});

describe("renderBranchHistory", () => {
  it("renders an active branch with no turns yet", () => {
    const out = renderBranchHistory(
      fakeState([fakeBranch({ id: "B1" })]),
    );
    expect(out).toContain("B1");
    expect(out).toContain("ACTIVE");
    expect(out).toContain("no turns yet");
  });

  it("renders a culled branch with reason and artifact count", () => {
    const out = renderBranchHistory(
      fakeState([
        fakeBranch({
          id: "B1",
          status: "culled",
          inactiveReason: "culled after 3 consecutive failures",
          turns: [
            { turn: 1, toolCall: { name: "verify_smt", args: {} }, result: "" },
            { turn: 2, toolCall: { name: "verify_smt", args: {} }, result: "" },
            { turn: 3, toolCall: { name: "verify_smt", args: {} }, result: "" },
          ],
          verifiedArtifacts: [
            {
              kind: "smt",
              claim: "x",
              code: "y",
              verdict: "unsat",
              claimStatus: "refuted",
            },
          ],
        }),
      ]),
    );
    expect(out).toContain("B1");
    expect(out).toContain("CULLED");
    expect(out).toContain("3 turn(s)");
    expect(out).toContain("1 artifact(s)");
    expect(out).toContain("culled after 3 consecutive failures");
  });

  it("renders multiple branches preserving order", () => {
    const out = renderBranchHistory(
      fakeState([
        fakeBranch({ id: "B1", status: "culled", inactiveReason: "x" }),
        fakeBranch({ id: "B2", status: "abandoned", inactiveReason: "y" }),
        fakeBranch({ id: "B3", status: "done" }),
      ]),
    );
    const lines = out.split("\n");
    expect(lines).toHaveLength(3);
    expect(lines[0]).toContain("B1");
    expect(lines[1]).toContain("B2");
    expect(lines[2]).toContain("B3");
  });
});
