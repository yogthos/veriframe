import { describe, it, expect } from "vitest";
import {
  buildSystemPrompt,
  buildStepPrompt,
  buildFixPrompt,
} from "../src/harness/prompts.js";
import type { ReasoningStep } from "../src/types.js";

describe("buildSystemPrompt", () => {
  it("includes the output format specification", () => {
    const prompt = buildSystemPrompt();
    expect(prompt).toMatch(/explanation/);
    expect(prompt).toMatch(/assertions/);
    expect(prompt).toMatch(/complete/);
    expect(prompt).toMatch(/SMT-LIB/);
  });

  it("includes instructions about incremental reasoning", () => {
    const prompt = buildSystemPrompt();
    expect(prompt).toMatch(/step/);
    expect(prompt).toMatch(/verify|check|consistent/);
  });
});

describe("buildStepPrompt", () => {
  const problem = "Prove that all users in the system have unique IDs";
  const acceptedSteps: ReasoningStep[] = [
    {
      stepNumber: 1,
      explanation: "Declared User sort and id function",
      assertions: [
        "(declare-sort User 0)",
        "(declare-fun id (User) Int)",
        "(declare-const u1 User)",
        "(declare-const u2 User)",
        "(assert (! (not (= u1 u2)) :named distinct_users))",
      ],
      status: "accepted",
    },
  ];

  it("includes the problem description", () => {
    const prompt = buildStepPrompt(problem, acceptedSteps, 2);
    expect(prompt).toContain(problem);
  });

  it("includes accepted steps history", () => {
    const prompt = buildStepPrompt(problem, acceptedSteps, 2);
    expect(prompt).toContain("distinct_users");
    expect(prompt).toContain("Declared User sort");
  });

  it("indicates the current step number", () => {
    const prompt = buildStepPrompt(problem, acceptedSteps, 5);
    expect(prompt).toContain("5");
  });

  it("handles empty history", () => {
    const prompt = buildStepPrompt(problem, [], 1);
    expect(prompt).toContain(problem);
    expect(prompt).toContain("1");
  });
});

describe("buildFixPrompt", () => {
  const problem = "Determine if admin always has access";
  const failedAssertions = [
    "(assert (! (> x 10) :named gt10))",
    "(assert (! (< x 5) :named lt5))",
  ];
  const unsatCore = ["gt10", "lt5"];
  const history: ReasoningStep[] = [
    {
      stepNumber: 1,
      explanation: "Declared x",
      assertions: ["(declare-const x Int)"],
      status: "accepted",
    },
  ];

  it("includes the unsat core", () => {
    const prompt = buildFixPrompt(
      problem,
      failedAssertions,
      unsatCore,
      history,
      2
    );
    expect(prompt).toContain("gt10");
    expect(prompt).toContain("lt5");
  });

  it("includes the failed assertions", () => {
    const prompt = buildFixPrompt(
      problem,
      failedAssertions,
      unsatCore,
      history,
      2
    );
    expect(prompt).toContain("(> x 10)");
    expect(prompt).toContain("(< x 5)");
  });

  it("tells the LLM to fix the contradiction", () => {
    const prompt = buildFixPrompt(
      problem,
      failedAssertions,
      unsatCore,
      history,
      2
    );
    expect(prompt).toMatch(/fix|resolve|correct|revise/);
  });

  it("includes accepted history for context", () => {
    const prompt = buildFixPrompt(
      problem,
      failedAssertions,
      unsatCore,
      history,
      2
    );
    expect(prompt).toContain("Declared x");
  });
});
