/**
 * Unit tests for agent.ts helpers we want to lock in.
 * Avoids spinning up the full agent run.
 */

import { describe, it, expect } from "vitest";
import {
  leanSnippetHasImport,
  renderBranchHistory,
  type AgentSession,
} from "../src/harness/agent.js";

function fakeSession(branches: AgentSession["branches"]): AgentSession {
  // Type-safe construction without spinning up Prolog; only the
  // branches field matters for renderBranchHistory.
  return { branches } as unknown as AgentSession;
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
  it("renders an active branch with no artifacts", () => {
    const out = renderBranchHistory(
      fakeSession([
        {
          id: "B1",
          hypothesis: "Behrend digit-sum lift",
          status: "active",
          startedAtTurn: 1,
          artifactCount: 0,
        },
      ]),
    );
    expect(out).toContain("B1");
    expect(out).toContain("ACTIVE");
    expect(out).toContain("Behrend digit-sum lift");
    expect(out).toContain("turn 1+");
  });

  it("renders an abandoned branch with reason and turn range", () => {
    const out = renderBranchHistory(
      fakeSession([
        {
          id: "B1",
          hypothesis: "Behrend digit-sum lift",
          status: "abandoned",
          startedAtTurn: 1,
          endedAtTurn: 5,
          abandonReason: "off-by-one in digit lift; size 36 set has a 3-AP",
          artifactCount: 1,
        },
        {
          id: "B2",
          hypothesis: "Cantor middle-thirds",
          status: "active",
          startedAtTurn: 6,
          artifactCount: 0,
        },
      ]),
    );
    expect(out).toContain("B1");
    expect(out).toContain("ABANDONED");
    expect(out).toContain("turns 1-5");
    expect(out).toContain("1 artifact(s)");
    expect(out).toContain("off-by-one in digit lift");
    expect(out).toContain("B2");
    expect(out).toContain("ACTIVE");
  });

  it("renders multiple branches preserving order", () => {
    const out = renderBranchHistory(
      fakeSession([
        { id: "B1", hypothesis: "first", status: "abandoned", startedAtTurn: 1, endedAtTurn: 3, abandonReason: "wrong", artifactCount: 0 },
        { id: "B2", hypothesis: "second", status: "abandoned", startedAtTurn: 4, endedAtTurn: 7, abandonReason: "also wrong", artifactCount: 0 },
        { id: "B3", hypothesis: "third", status: "active", startedAtTurn: 8, artifactCount: 0 },
      ]),
    );
    const lines = out.split("\n");
    expect(lines).toHaveLength(3);
    expect(lines[0]).toContain("B1");
    expect(lines[1]).toContain("B2");
    expect(lines[2]).toContain("B3");
  });
});
