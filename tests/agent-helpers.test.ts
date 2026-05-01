/**
 * Unit tests for agent.ts helpers we want to lock in.
 * Avoids spinning up the full agent run.
 */

import { describe, it, expect } from "vitest";
import { leanSnippetHasImport } from "../src/harness/agent.js";

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
