import { describe, it, expect } from "vitest";
import { parseLLMOutput, validateAssertion } from "../src/harness/parser.js";
import type { LLMStepOutput } from "../src/types.js";

describe("validateAssertion", () => {
  it("accepts simple declare-const", () => {
    expect(validateAssertion("(declare-const x Int)")).toBeNull();
  });

  it("accepts declare-sort", () => {
    const err = validateAssertion("(declare-sort User 0)");
    expect(err).toBeNull();
  });

  it("accepts declare-fun", () => {
    const err = validateAssertion("(declare-fun f (Int) Bool)");
    expect(err).toBeNull();
  });

  it("accepts assert with expression", () => {
    const err = validateAssertion("(assert (> x 0))");
    expect(err).toBeNull();
  });

  it("accepts named assert", () => {
    const err = validateAssertion("(assert (! (> x 10) :named gt10))");
    expect(err).toBeNull();
  });

  it("accepts assert with forall", () => {
    const err = validateAssertion(
      "(assert (forall ((u User)) (authenticated u)))"
    );
    expect(err).toBeNull();
  });

  it("rejects empty string", () => {
    const err = validateAssertion("");
    expect(err).toBe("Assertion is empty");
  });

  it("rejects whitespace-only string", () => {
    const err = validateAssertion("   \n  ");
    expect(err).toBe("Assertion is empty");
  });

  it("rejects unbalanced parentheses", () => {
    const err = validateAssertion("(assert (> x 0)");
    expect(err).toMatch(/unbalanced|mismatch|parenthes/i);
  });

  it("rejects non-SMT-LIB text", () => {
    const err = validateAssertion("hello world");
    expect(err).toMatch(/parenthes|SMT-LIB/i);
  });
});

describe("parseLLMOutput", () => {
  it("parses valid JSON with assertions", () => {
    const input = JSON.stringify({
      explanation: "Basic constraints on x",
      assertions: ["(declare-const x Int)", "(assert (> x 0))"],
      complete: false,
    });
    const result = parseLLMOutput(input);
    expect(result).not.toBeNull();
    expect(result!.explanation).toBe("Basic constraints on x");
    expect(result!.assertions).toHaveLength(2);
    expect(result!.complete).toBe(false);
  });

  it("parses empty assertions array", () => {
    const input = JSON.stringify({
      explanation: "No new facts",
      assertions: [],
      complete: false,
    });
    const result = parseLLMOutput(input);
    expect(result).not.toBeNull();
    expect(result!.assertions).toHaveLength(0);
  });

  it("parses complete flag", () => {
    const input = JSON.stringify({
      explanation: "Done",
      assertions: [],
      complete: true,
    });
    const result = parseLLMOutput(input);
    expect(result!.complete).toBe(true);
  });

  it("returns null for invalid JSON", () => {
    const result = parseLLMOutput("not json at all");
    expect(result).toBeNull();
  });

  it("returns null for JSON missing assertions field", () => {
    const input = JSON.stringify({ explanation: "no assertions key" });
    const result = parseLLMOutput(input);
    expect(result).toBeNull();
  });

  it("returns null when assertions is not an array", () => {
    const input = JSON.stringify({
      explanation: "bad format",
      assertions: "not an array",
      complete: false,
    });
    const result = parseLLMOutput(input);
    expect(result).toBeNull();
  });

  it("extracts JSON from text with markdown fences", () => {
    const input = [
      "```json",
      JSON.stringify({
        explanation: "Basic constraints",
        assertions: ["(assert (> x 0))"],
        complete: false,
      }),
      "```",
      "some trailing text",
    ].join("\n");
    const result = parseLLMOutput(input);
    expect(result).not.toBeNull();
    expect(result!.explanation).toBe("Basic constraints");
  });

  it("parses JSON with extra whitespace", () => {
    const input = `  \n ${JSON.stringify({
      explanation: "Constraints",
      assertions: ["(assert (> x 0))"],
      complete: false,
    })} \n  `;
    const result = parseLLMOutput(input);
    expect(result).not.toBeNull();
  });

  it("validates assertions in the output", () => {
    const input = JSON.stringify({
      explanation: "Has bad assertion",
      assertions: ["(assert (> x 0))", "bad (expr"],
      complete: false,
    });
    const result = parseLLMOutput(input);
    expect(result).toBeNull(); // Should reject due to invalid assertion
  });

  it("defaults complete to false when missing", () => {
    const input = JSON.stringify({
      explanation: "Missing complete field",
      assertions: [],
    });
    const result = parseLLMOutput(input);
    expect(result).not.toBeNull();
    expect(result!.complete).toBe(false);
  });
});
