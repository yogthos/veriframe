/**
 * Unit tests for agent.ts helpers we want to lock in.
 * Avoids spinning up the full agent run.
 */

import { describe, it, expect } from "vitest";
import {
  leanSnippetHasImport,
  renderBranchHistory,
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
