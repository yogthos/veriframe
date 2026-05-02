/**
 * Unit tests for the OpenAI-compat factory's pure helpers.
 * The HTTP path itself is exercised end-to-end via the GLM/DeepSeek
 * providers in benchmark runs; here we lock in the logic the
 * factory does locally.
 */

import { describe, it, expect } from "vitest";
import { stripThinkBlocks } from "../src/llm/openai-compat.js";

describe("stripThinkBlocks", () => {
  it("removes a simple think block", () => {
    expect(stripThinkBlocks("<think>reasoning here</think>final answer"))
      .toBe("final answer");
  });

  it("removes multi-line think blocks", () => {
    const input = "<think>line1\nline2\nline3</think>\nthe answer";
    expect(stripThinkBlocks(input)).toBe("the answer");
  });

  it("removes multiple think blocks", () => {
    const input = "<think>a</think>middle<think>b</think>end";
    expect(stripThinkBlocks(input)).toBe("middleend");
  });

  it("leaves content without think blocks unchanged (modulo trim)", () => {
    expect(stripThinkBlocks("plain text content")).toBe("plain text content");
  });

  it("handles empty string", () => {
    expect(stripThinkBlocks("")).toBe("");
  });

  it("returns empty when content is only think", () => {
    expect(stripThinkBlocks("<think>only thinking, no answer</think>"))
      .toBe("");
  });

  it("preserves a tool-call fence after the think block", () => {
    // The agent's TOOL_CALL_FENCE_RE looks for `\`\`\`tool-call`
    // anywhere in the content. After stripping, the fence must
    // still be there.
    const input = '<think>let me reason</think>\n\n```tool-call\n{"name": "done"}\n```';
    const result = stripThinkBlocks(input);
    expect(result).toContain("```tool-call");
    expect(result).toContain('"name": "done"');
  });
});
